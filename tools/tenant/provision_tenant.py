#!/usr/bin/env python3
"""One-step tenant provisioning (US-001).

A tenant is described by a reviewed spec file, infra/tenants/<environment>/<code>.json (see
infra/tenants/README.md). Merging that file is the one action; this tool, run by the provision-tenant workflow
on a runner inside the environment's VPC, then performs every step of docs/runbooks/tenant-onboarding.md:

  1. validate        spec checks (code, tier, edition, head office, admins, URLs)
  2. terraform       tenants.auto.tfvars.json generated from ALL spec files of the environment, plan + apply
                     (KMS key, DB secret, IAM policy - Terraform modules/tenant)
  3. database        role + database on Aurora from the tenant secret, pgaudit (idempotent)
  4. realm           Keycloak realm rendered from the security baseline, service-client secret rotated into
                     Secrets Manager, first TENANT_ADMIN users with a temporary password (stored in Secrets
                     Manager for out-of-band delivery, never printed)
  5. credentials     tenant added to the Helm value tenantDbSecrets.tenants, External Secrets synced, pods restarted
  6. control-plane   POST /platform/v1/tenants: control.tenant row, migrations, starter kit, first staff profiles
  7. verify          tenant ACTIVE with the expected modules

Every step is idempotent, so a failed run is simply re-run (optionally --from-step). STANDALONE tenants skip
steps 2, 3 and 5 (the bank's own infrastructure; see values-standalone.yaml).

Secrets never appear in the spec, arguments or output. The tool reads:
  KEYCLOAK_ADMIN_CLIENT_ID / KEYCLOAK_ADMIN_CLIENT_SECRET   master-realm client allowed to create realms
  COREBANKING_OPERATOR_TOKEN                                bearer token with platform:operator
AWS access comes from the runner's role (OIDC); the DB master secret is read from Secrets Manager.

Usage:
  provision_tenant.py infra/tenants/prod/acme-finance.json [--from-step realm] [--plan-only]
  provision_tenant.py --self-test

Standard library only (Python 3.9+). External commands: terraform, aws, psql, helm, kubectl.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

REPO = Path(__file__).resolve().parents[2]
STEPS = ["validate", "terraform", "database", "realm", "credentials", "control-plane", "verify"]
CLOUD_ONLY = {"terraform", "database", "credentials"}
TENANT_CODE = re.compile(r"^[a-z][a-z0-9-]{2,30}$")
USERNAME = re.compile(r"^[A-Za-z0-9._@-]{2,80}$")
ENTITY_TYPES = {"NBFC", "BANK", "SFB", "COOP_BANK", "HFC", "MFI"}
TIERS = {"POOLED", "DEDICATED", "STANDALONE"}
ENVIRONMENTS = {"sandbox", "uat", "prod"}


class ProvisioningError(Exception):
    pass


# ------------------------------------------------------------------------------------------------ plumbing
@dataclass
class Result:
    code: int
    out: str


class Shell:
    """Runs external commands. Values passed through `env`/`stdin` never reach argv (process lists, logs)."""

    def run(self, argv: list[str], *, stdin: str | None = None, env: dict[str, str] | None = None,
            check: bool = True, cwd: Path | None = None) -> Result:
        full_env = dict(os.environ)
        full_env.update(env or {})
        p = subprocess.run(argv, input=stdin, env=full_env, cwd=cwd, text=True, capture_output=True)
        if check and p.returncode != 0:
            raise ProvisioningError(f"{argv[0]} {argv[1] if len(argv) > 1 else ''} failed ({p.returncode}): "
                                    f"{redact(p.stderr.strip())[-800:]}")
        return Result(p.returncode, p.stdout)


class Http:
    def request(self, method: str, url: str, *, token: str | None = None, body: Any = None,
                form: dict[str, str] | None = None) -> tuple[int, Any]:
        data, headers = None, {"Accept": "application/json"}
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = "Bearer " + token
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                raw = r.read().decode() or "null"
                return r.status, json.loads(raw) if raw.strip().startswith(("{", "[")) else raw
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                return e.code, json.loads(raw)
            except ValueError:
                return e.code, raw


def redact(text: str) -> str:
    text = re.sub(r"(?i)(password|secret|token)([\"'=: ]+)[^\s\"',}]+", r"\1\2***", text)
    return text


def log(msg: str) -> None:
    print(msg, flush=True)


# ------------------------------------------------------------------------------------------------ spec
@dataclass
class Spec:
    raw: dict
    path: Path

    def __getitem__(self, k: str) -> Any:
        return self.raw[k]

    def get(self, k: str, default: Any = None) -> Any:
        return self.raw.get(k, default)

    @property
    def code(self) -> str:
        return self.raw["code"]

    @property
    def environment(self) -> str:
        return self.raw["environment"]

    @property
    def standalone(self) -> bool:
        return self.raw["deploymentTier"] == "STANDALONE"

    @property
    def db_identifier(self) -> str:
        return "tenant_" + self.code.replace("-", "_")

    def section(self, name: str) -> dict:
        return self.raw.get(name) or {}


def load_spec(path: Path) -> Spec:
    try:
        raw = json.loads(path.read_text())
    except (OSError, ValueError) as e:
        raise ProvisioningError(f"cannot read spec {path}: {e}")
    spec = Spec(raw, path)
    validate(spec)
    return spec


def validate(spec: Spec) -> None:
    r, errors = spec.raw, []
    if not TENANT_CODE.match(str(r.get("code", ""))):
        errors.append("code must match ^[a-z][a-z0-9-]{2,30}$")
    if r.get("code") == "platform":
        errors.append("'platform' is the operators' realm and cannot be a tenant code")
    if spec.path.stem != r.get("code"):
        errors.append(f"file name must be <code>.json (got {spec.path.name})")
    if r.get("environment") not in ENVIRONMENTS:
        errors.append(f"environment must be one of {sorted(ENVIRONMENTS)}")
    if spec.path.parent.name != r.get("environment"):
        errors.append("the spec must live in infra/tenants/<environment>/")
    if not str(r.get("legalName", "")).strip():
        errors.append("legalName is required")
    if r.get("entityType") not in ENTITY_TYPES:
        errors.append(f"entityType must be one of {sorted(ENTITY_TYPES)}")
    if r.get("deploymentTier") not in TIERS:
        errors.append(f"deploymentTier must be one of {sorted(TIERS)}")
    if not re.match(r"^[A-Z][A-Z0-9_]{1,30}$", str(r.get("edition", ""))):
        errors.append("edition is required (e.g. GROWTH)")
    ho = r.get("headOffice") or {}
    if not re.match(r"^[A-Z0-9]{2,10}$", str(ho.get("code", ""))) or not ho.get("name"):
        errors.append("headOffice.code (2-10 capitals/digits) and headOffice.name are required")
    if not re.match(r"^[0-9]{2}$", str(ho.get("stateCode", ""))):
        errors.append("headOffice.stateCode is the two-digit GST state code, e.g. 32")
    admins = r.get("admins") or []
    if not admins:
        errors.append("at least one admin is required")
    for a in admins:
        if not USERNAME.match(str(a.get("username", ""))):
            errors.append(f"admin username {a.get('username')!r} is invalid")
        if not re.match(r"^[^@\s]+@[^@\s]+\.[^@\s]+$", str(a.get("email", ""))):
            errors.append(f"admin {a.get('username')!r} needs a work email for the temporary password")
    for key in ("consoleUrl",):
        u = urllib.parse.urlparse(str(r.get(key, "")))
        if u.scheme != "https" or not u.netloc:
            errors.append(f"{key} must be an https URL")
    for sect, key in (("keycloak", "url"), ("api", "url")):
        u = urllib.parse.urlparse(str(spec.section(sect).get(key, "")))
        if u.scheme != "https" or not u.netloc:
            errors.append(f"{sect}.{key} must be an https URL")
    if not spec.standalone:
        if not spec.section("aws").get("masterSecretId"):
            errors.append("aws.masterSecretId (Aurora master secret) is required for cloud tiers")
        k8s = spec.section("kubernetes")
        if not k8s.get("namespace") or not k8s.get("release"):
            errors.append("kubernetes.namespace and kubernetes.release are required for cloud tiers")
    if errors:
        raise ProvisioningError("invalid tenant spec:\n  - " + "\n  - ".join(errors))


# ------------------------------------------------------------------------------------------------ steps
@dataclass
class Context:
    spec: Spec
    shell: Shell
    http: Http
    env: dict[str, str]
    plan_only: bool = False
    tf_outputs: dict = field(default_factory=dict)
    repo: Path = REPO

    def need(self, name: str) -> str:
        v = self.env.get(name)
        if not v:
            raise ProvisioningError(f"environment variable {name} is required for this step")
        return v

    @property
    def region(self) -> str:
        return self.spec.section("aws").get("region", "ap-south-1")

    @property
    def standalone_secret_store(self) -> bool:
        """True when there is no AWS Secrets Manager (STANDALONE tier: the bank's own vault)."""
        return self.spec.standalone


def terraform_tenants(env_dir: Path) -> dict:
    """The Terraform `tenants` map for an environment, derived from its reviewed spec files."""
    tenants = {}
    for f in sorted(env_dir.glob("*.json")):
        s = json.loads(f.read_text())
        if s.get("deploymentTier") == "STANDALONE":
            continue
        entry = {"attach_policy_to_app_role": s.get("deploymentTier") in ("POOLED", "DEDICATED")}
        replica = s.get("aws", {}).get("secretReplicaKmsKeyArn")
        if replica:
            entry["secret_replica_kms_key_arn"] = replica
        tenants[s["code"]] = entry
    return tenants


def step_terraform(ctx: Context) -> None:
    spec = ctx.spec
    tf_dir = ctx.repo / "infra" / "terraform" / "envs" / spec.environment
    tenants = terraform_tenants(ctx.repo / "infra" / "tenants" / spec.environment)
    if spec.code not in tenants:
        raise ProvisioningError(f"{spec.code} is not among the spec files of {spec.environment}")
    (tf_dir / "tenants.auto.tfvars.json").write_text(json.dumps({"tenants": tenants}, indent=2) + "\n")
    log(f"  tenants.auto.tfvars.json: {len(tenants)} tenant(s)")
    ctx.shell.run(["terraform", f"-chdir={tf_dir}", "init", "-input=false", "-backend-config=backend.hcl"])
    ctx.shell.run(["terraform", f"-chdir={tf_dir}", "plan", "-input=false", "-out=tenant.tfplan"])
    if ctx.plan_only:
        log("  --plan-only: plan written, not applied")
        return
    ctx.shell.run(["terraform", f"-chdir={tf_dir}", "apply", "-input=false", "tenant.tfplan"])
    out = json.loads(ctx.shell.run(["terraform", f"-chdir={tf_dir}", "output", "-json", "tenants"]).out or "{}")
    if spec.code not in out:
        raise ProvisioningError("terraform output has no entry for " + spec.code)
    ctx.tf_outputs = out[spec.code]
    log(f"  key {ctx.tf_outputs['kms_key_arn']}, secret {ctx.tf_outputs['db_secret_arn']}")


def read_outputs(ctx: Context) -> dict:
    if not ctx.tf_outputs and not ctx.spec.standalone:
        tf_dir = ctx.repo / "infra" / "terraform" / "envs" / ctx.spec.environment
        out = json.loads(ctx.shell.run(["terraform", f"-chdir={tf_dir}", "output", "-json", "tenants"]).out or "{}")
        ctx.tf_outputs = out.get(ctx.spec.code, {})
    return ctx.tf_outputs


def secret_json(ctx: Context, secret_id: str) -> dict:
    r = ctx.shell.run(["aws", "secretsmanager", "get-secret-value", "--region", ctx.region, "--secret-id", secret_id,
                       "--query", "SecretString", "--output", "text"])
    return json.loads(r.out)


def put_secret(ctx: Context, name: str, value: dict, kms_key_arn: str | None) -> None:
    """Creates or updates a secret; the value goes through stdin, never argv."""
    payload = json.dumps(value)
    exists = ctx.shell.run(["aws", "secretsmanager", "describe-secret", "--region", ctx.region, "--secret-id", name],
                           check=False).code == 0
    if exists:
        ctx.shell.run(["aws", "secretsmanager", "put-secret-value", "--region", ctx.region, "--secret-id", name,
                       "--secret-string", "file:///dev/stdin"], stdin=payload)
    else:
        argv = ["aws", "secretsmanager", "create-secret", "--region", ctx.region, "--name", name,
                "--secret-string", "file:///dev/stdin"]
        if kms_key_arn:
            argv += ["--kms-key-id", kms_key_arn]
        ctx.shell.run(argv, stdin=payload)


DATABASE_SQL = r"""
\set ON_ERROR_STOP on
\getenv tenant_pw TENANT_DB_PASSWORD
SELECT format('CREATE ROLE %I LOGIN', :'db_user') WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'db_user') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN PASSWORD %L', :'db_user', :'tenant_pw') \gexec
SELECT format('GRANT %I TO current_user', :'db_user') \gexec
SELECT format('CREATE DATABASE %I OWNER %I', :'db_name', :'db_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'db_name') \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', :'db_name') \gexec
\c :db_name
CREATE EXTENSION IF NOT EXISTS pgaudit;
"""


def step_database(ctx: Context) -> None:
    spec = ctx.spec
    outputs = read_outputs(ctx)
    tenant = secret_json(ctx, outputs["db_secret_arn"])
    master = secret_json(ctx, spec.section("aws")["masterSecretId"])
    env = {"PGPASSWORD": master["password"], "TENANT_DB_PASSWORD": tenant["password"],
           "PGSSLMODE": "verify-full", "PGCONNECT_TIMEOUT": "15"}
    if spec.section("aws").get("rdsCaBundle"):
        env["PGSSLROOTCERT"] = spec.section("aws")["rdsCaBundle"]
    ctx.shell.run(["psql", "-X", "-q", "-h", tenant["host"], "-p", str(tenant.get("port", 5432)), "-U", master["username"],
                   "-d", "postgres", "-v", f"db_user={tenant['username']}", "-v", f"db_name={tenant['dbname']}"],
                  stdin=DATABASE_SQL, env=env)
    log(f"  database {tenant['dbname']} and role ready")


def load_realm_renderer(repo: Path):
    path = repo / "infra" / "keycloak" / "new-tenant-realm.py"
    s = importlib.util.spec_from_file_location("new_tenant_realm", path)
    mod = importlib.util.module_from_spec(s)
    assert s.loader is not None
    s.loader.exec_module(mod)
    return mod


def keycloak_token(ctx: Context) -> str:
    kc = ctx.spec.section("keycloak")["url"].rstrip("/")
    status, body = ctx.http.request("POST", f"{kc}/realms/master/protocol/openid-connect/token", form={
        "grant_type": "client_credentials",
        "client_id": ctx.need("KEYCLOAK_ADMIN_CLIENT_ID"),
        "client_secret": ctx.need("KEYCLOAK_ADMIN_CLIENT_SECRET")})
    if status != 200:
        raise ProvisioningError(f"Keycloak admin login failed ({status})")
    return body["access_token"]


def step_realm(ctx: Context) -> None:
    spec, http = ctx.spec, ctx.http
    kc = spec.section("keycloak")["url"].rstrip("/")
    admin = f"{kc}/admin/realms"
    token = keycloak_token(ctx)
    code = spec.code
    status, _ = http.request("GET", f"{admin}/{code}", token=token)
    if status == 404:
        renderer = load_realm_renderer(ctx.repo)
        template = json.loads(renderer.DEFAULT_TEMPLATE.read_text())
        realm = renderer.render(template, code, spec["consoleUrl"], spec["legalName"], keep_demo_users=False)
        status, body = http.request("POST", admin, token=token, body=realm)
        if status not in (201, 204):
            raise ProvisioningError(f"realm import failed ({status}): {redact(str(body))[:300]}")
        log(f"  realm {code} created from the security baseline")
    elif status == 200:
        log(f"  realm {code} exists")
    else:
        raise ProvisioningError(f"cannot read realm {code} ({status})")

    # Integration client secret: rotated once, kept in Secrets Manager (never the template placeholder).
    outputs = read_outputs(ctx)
    secret_name = f"corebanking/{spec.environment}/tenants/{code}/service-client"
    if ctx.standalone_secret_store:
        log("  STANDALONE: rotate the corebanking-service secret into the bank's vault (runbook step 5.2)")
        have = True
    else:
        have = ctx.shell.run(["aws", "secretsmanager", "describe-secret", "--region", ctx.region,
                              "--secret-id", secret_name], check=False).code == 0
    if not have:
        status, clients = http.request("GET", f"{admin}/{code}/clients?clientId=corebanking-service", token=token)
        if status != 200 or not clients:
            raise ProvisioningError("service client corebanking-service not found in the realm")
        cid = clients[0]["id"]
        status, cred = http.request("POST", f"{admin}/{code}/clients/{cid}/client-secret", token=token)
        if status != 200:
            raise ProvisioningError(f"cannot rotate the service client secret ({status})")
        put_secret(ctx, secret_name, {"clientId": "corebanking-service", "clientSecret": cred["value"]},
                   outputs.get("kms_key_arn"))
        log(f"  service client secret rotated -> {secret_name}")

    # First tenant admins: temporary password + forced password change and TOTP enrolment.
    status, role = http.request("GET", f"{admin}/{code}/roles/TENANT_ADMIN", token=token)
    if status != 200:
        raise ProvisioningError("realm role TENANT_ADMIN not found")
    created = {}
    for a in spec["admins"]:
        username = a["username"].lower()
        status, found = http.request("GET", f"{admin}/{code}/users?exact=true&username={urllib.parse.quote(username)}",
                                     token=token)
        if status == 200 and found:
            continue
        status, _ = http.request("POST", f"{admin}/{code}/users", token=token, body={
            "username": username, "email": a["email"], "emailVerified": True, "enabled": True,
            "firstName": a.get("firstName", ""), "lastName": a.get("lastName", ""),
            "requiredActions": ["UPDATE_PASSWORD", "CONFIGURE_TOTP"]})
        if status != 201:
            raise ProvisioningError(f"cannot create admin {username} ({status})")
        _, found = http.request("GET", f"{admin}/{code}/users?exact=true&username={urllib.parse.quote(username)}", token=token)
        uid = found[0]["id"]
        status, _ = http.request("POST", f"{admin}/{code}/users/{uid}/role-mappings/realm", token=token, body=[role])
        if status not in (200, 204):
            raise ProvisioningError(f"cannot grant TENANT_ADMIN to {username} ({status})")
        if ctx.standalone_secret_store:
            log(f"  admin {username} created without a password; the bank sets it (Keycloak: Credentials > Reset)")
            continue
        temp = secrets.token_urlsafe(18) + "-9a"
        status, _ = http.request("PUT", f"{admin}/{code}/users/{uid}/reset-password", token=token,
                                 body={"type": "password", "value": temp, "temporary": True})
        if status not in (200, 204):
            raise ProvisioningError(f"cannot set the temporary password of {username} ({status})")
        created[username] = temp
    if created:
        put_secret(ctx, f"corebanking/{spec.environment}/tenants/{code}/initial-admins", created, outputs.get("kms_key_arn"))
        log(f"  {len(created)} admin(s) created; temporary passwords in corebanking/{spec.environment}/tenants/{code}/initial-admins")


def step_credentials(ctx: Context) -> None:
    spec = ctx.spec
    k8s = spec.section("kubernetes")
    ns, release = k8s["namespace"], k8s["release"]
    chart = str(ctx.repo / k8s.get("chart", "infra/helm/corebanking"))
    values = json.loads(ctx.shell.run(["helm", "get", "values", release, "-n", ns, "-o", "json"]).out or "{}") or {}
    tdb = values.get("tenantDbSecrets") or {}
    if not tdb.get("enabled"):
        raise ProvisioningError("the release does not have tenantDbSecrets.enabled=true (values file of the environment)")
    tenants = list(tdb.get("tenants") or [])
    if spec.code not in tenants:
        tenants = sorted(tenants + [spec.code])
        ctx.shell.run(["helm", "upgrade", release, chart, "-n", ns, "--reuse-values",
                       "--set-json", "tenantDbSecrets.tenants=" + json.dumps(tenants), "--wait", "--timeout", "15m"])
        log(f"  {spec.code} added to tenantDbSecrets.tenants ({len(tenants)} tenants)")
    name = k8s.get("fullname", release) + "-tenant-db"
    ctx.shell.run(["kubectl", "-n", ns, "annotate", "externalsecret", name, "force-sync=" + secrets.token_hex(4), "--overwrite"])
    ctx.shell.run(["kubectl", "-n", ns, "wait", "--for=condition=Ready", f"externalsecret/{name}", "--timeout=180s"])
    var = "COREBANKING_TENANT_" + spec.code.upper().replace("-", "_") + "_URL"
    keys = ctx.shell.run(["kubectl", "-n", ns, "get", "secret", name, "-o", "jsonpath={.data}"]).out
    if var not in keys:
        raise ProvisioningError(f"secret {name} has no {var} yet; check the ExternalSecret status")
    deploy = k8s.get("fullname", release)
    ctx.shell.run(["kubectl", "-n", ns, "rollout", "restart", f"deployment/{deploy}"])
    ctx.shell.run(["kubectl", "-n", ns, "rollout", "status", f"deployment/{deploy}", "--timeout=15m"])
    log("  backend restarted with the tenant's credentials")


def provisioning_request(spec: Spec, outputs: dict) -> dict:
    return {
        "code": spec.code, "legalName": spec["legalName"], "entityType": spec["entityType"],
        "deploymentTier": spec["deploymentTier"], "edition": spec["edition"], "starterKit": spec.get("starterKit"),
        "headOffice": spec["headOffice"], "firstBusinessDate": spec.get("firstBusinessDate"),
        "adminUsers": [a["username"].lower() for a in spec["admins"]],
        "kmsKeyArn": outputs.get("kms_key_arn"), "dbSecretArn": outputs.get("db_secret_arn")}


def find_tenant(ctx: Context, token: str) -> dict | None:
    api = ctx.spec.section("api")["url"].rstrip("/")
    status, tenants = ctx.http.request("GET", f"{api}/platform/v1/tenants", token=token)
    if status != 200:
        raise ProvisioningError(f"cannot list tenants ({status})")
    return next((t for t in tenants if t.get("code") == ctx.spec.code), None)


def step_control_plane(ctx: Context) -> None:
    token = ctx.need("COREBANKING_OPERATOR_TOKEN")
    existing = find_tenant(ctx, token)
    if existing:
        log(f"  tenant already registered ({existing.get('status')})")
        return
    api = ctx.spec.section("api")["url"].rstrip("/")
    status, body = ctx.http.request("POST", f"{api}/platform/v1/tenants", token=token,
                                    body=provisioning_request(ctx.spec, read_outputs(ctx)))
    if status not in (200, 201):
        raise ProvisioningError(f"provisioning API failed ({status}): {redact(str(body))[:400]}")
    log(f"  tenant registered: status {body.get('status')}, modules {body.get('modules')}")


def step_verify(ctx: Context) -> None:
    t = find_tenant(ctx, ctx.need("COREBANKING_OPERATOR_TOKEN"))
    if not t or t.get("status") != "ACTIVE":
        raise ProvisioningError(f"tenant {ctx.spec.code} is not ACTIVE: {t}")
    log(f"  {ctx.spec.code} is ACTIVE with modules {t.get('modules')}")


STEP_FUNCS: dict[str, Callable[[Context], None]] = {
    "validate": lambda ctx: log(f"  spec {ctx.spec.path.name} is valid"),
    "terraform": step_terraform,
    "database": step_database,
    "realm": step_realm,
    "credentials": step_credentials,
    "control-plane": step_control_plane,
    "verify": step_verify,
}


def run(ctx: Context, from_step: str = "validate") -> None:
    start = STEPS.index(from_step)
    for name in STEPS[start:]:
        if ctx.spec.standalone and name in CLOUD_ONLY:
            log(f"[skip] {name} (STANDALONE)")
            continue
        log(f"[step] {name}")
        STEP_FUNCS[name](ctx)
        if ctx.plan_only and name == "terraform":
            log("stopping after the Terraform plan (--plan-only)")
            return
    log(f"tenant {ctx.spec.code} provisioned in {ctx.spec.environment}")


# ------------------------------------------------------------------------------------------------ self-test
class FakeShell(Shell):
    def __init__(self, responses: dict[str, str] | None = None):
        self.calls: list[tuple[list[str], str | None, dict]] = []
        self.responses = responses or {}

    def run(self, argv, *, stdin=None, env=None, check=True, cwd=None):
        self.calls.append((argv, stdin, env or {}))
        joined = " ".join(argv)
        for pattern, out in self.responses.items():
            if pattern in joined:
                if out == "FAIL":
                    if check:
                        raise ProvisioningError(joined)
                    return Result(1, "")
                return Result(0, out)
        return Result(0, "")


class FakeHttp(Http):
    def __init__(self, routes: list[tuple[str, str, int, Any]]):
        self.routes = routes
        self.calls: list[tuple[str, str, Any]] = []

    def request(self, method, url, *, token=None, body=None, form=None):
        self.calls.append((method, url, body if body is not None else form))
        for m, fragment, status, resp in self.routes:
            if m == method and fragment in url:
                return status, resp
        return 404, None


def _write_spec(root: Path, env: str, spec: dict) -> Path:
    d = root / "infra" / "tenants" / env
    d.mkdir(parents=True, exist_ok=True)
    p = d / f"{spec['code']}.json"
    p.write_text(json.dumps(spec))
    return p


def self_test() -> int:
    base = {
        "code": "acme-finance", "environment": "sandbox", "legalName": "CLAUDE-TEST Acme Finance Ltd",
        "entityType": "NBFC", "deploymentTier": "POOLED", "edition": "GROWTH",
        "headOffice": {"code": "HO", "name": "Head Office", "stateCode": "32"},
        "admins": [{"username": "Acme.Admin", "email": "it@acme.example.in", "firstName": "Test", "lastName": "Admin"}],
        "consoleUrl": "https://acme-finance.console.example.in",
        "keycloak": {"url": "https://id.example.in"}, "api": {"url": "https://api.example.in"},
        "aws": {"region": "ap-south-1", "masterSecretId": "corebanking/corebanking-sandbox/master"},
        "kubernetes": {"namespace": "corebanking", "release": "corebanking"},
    }
    failures = 0

    def check(ok: bool, label: str) -> None:
        nonlocal failures
        print(("PASS " if ok else "FAIL ") + label)
        failures += 0 if ok else 1

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "infra" / "terraform" / "envs" / "sandbox").mkdir(parents=True)
        (root / "infra" / "keycloak").mkdir(parents=True)
        (root / "infra" / "keycloak" / "new-tenant-realm.py").write_text((REPO / "infra/keycloak/new-tenant-realm.py").read_text())
        (root / "deploy" / "local" / "keycloak").mkdir(parents=True)
        (root / "deploy/local/keycloak/demo-nbfc-realm.json").write_text((REPO / "deploy/local/keycloak/demo-nbfc-realm.json").read_text())
        p = _write_spec(root, "sandbox", base)
        _write_spec(root, "sandbox", dict(base, code="bank-one", deploymentTier="STANDALONE"))
        _write_spec(root, "sandbox", dict(base, code="dedi-co", deploymentTier="DEDICATED",
                                           aws=dict(base["aws"], secretReplicaKmsKeyArn="arn:aws:kms:ap-south-2:1:key/r")))
        spec = load_spec(p)
        check(True, "V1 valid spec loads")

        bad = dict(base, code="Bad_Code", admins=[], consoleUrl="http://x", entityType="SHOP")
        try:
            validate(Spec(bad, root / "infra/tenants/sandbox/Bad_Code.json"))
            check(False, "V2 invalid spec rejected")
        except ProvisioningError as e:
            msg = str(e)
            check(all(k in msg for k in ("code must match", "entityType", "at least one admin", "consoleUrl")),
                  "V2 invalid spec rejected with every reason")
        try:
            validate(Spec(dict(base, environment="prod"), p))
            check(False, "V3 environment must match folder")
        except ProvisioningError:
            check(True, "V3 environment must match folder")

        tenants = terraform_tenants(root / "infra" / "tenants" / "sandbox")
        check(set(tenants) == {"acme-finance", "dedi-co"}, "T1 STANDALONE tenants are not in Terraform")
        check(tenants["dedi-co"].get("secret_replica_kms_key_arn", "").startswith("arn:aws:kms:ap-south-2"),
              "T2 DR secret replica carried to Terraform")

        outputs = json.dumps({"acme-finance": {"kms_key_arn": "arn:aws:kms:ap-south-1:1:key/k",
                                               "db_secret_arn": "arn:aws:secretsmanager:ap-south-1:1:secret:db"}})
        shell = FakeShell({
            "output -json tenants": outputs,
            "--secret-id arn:aws:secretsmanager:ap-south-1:1:secret:db": json.dumps(
                {"host": "db.internal", "port": 5432, "username": "tenant_acme_finance", "dbname": "tenant_acme_finance",
                 "password": "Tenant-PW-1"}),
            "--secret-id corebanking/corebanking-sandbox/master": json.dumps({"username": "master", "password": "Master-PW-1"}),
            "describe-secret": "FAIL",
            "helm get values": json.dumps({"tenantDbSecrets": {"enabled": True, "tenants": ["demo-nbfc"]}}),
            "jsonpath={.data}": '{"COREBANKING_TENANT_ACME_FINANCE_URL":"x"}',
        })
        role = {"id": "r1", "name": "TENANT_ADMIN"}
        http = FakeHttp([
            ("POST", "/protocol/openid-connect/token", 200, {"access_token": "kc-token"}),
            ("GET", "/admin/realms/acme-finance/clients?clientId=corebanking-service", 200, [{"id": "c1"}]),
            ("POST", "/clients/c1/client-secret", 200, {"value": "rotated-secret"}),
            ("GET", "/admin/realms/acme-finance/roles/TENANT_ADMIN", 200, role),
            ("GET", "/admin/realms/acme-finance/users?exact=true", 200, []),
            ("POST", "/role-mappings/realm", 204, None),
            ("POST", "/admin/realms/acme-finance/users", 201, None),
            ("PUT", "/reset-password", 204, None),
            ("GET", "/admin/realms/acme-finance", 404, None),
            ("POST", "/admin/realms", 201, None),
            ("GET", "/platform/v1/tenants", 200, []),
            ("POST", "/platform/v1/tenants", 201, {"status": "ACTIVE", "modules": "CORE,GL,LENDING"}),
        ])
        env = {"KEYCLOAK_ADMIN_CLIENT_ID": "provisioner", "KEYCLOAK_ADMIN_CLIENT_SECRET": "kc-secret",
               "COREBANKING_OPERATOR_TOKEN": "op-token"}
        ctx = Context(spec, shell, http, env, repo=root)
        # Users lookup returns [] before creation and one user after: emulate by swapping the route after POST.
        orig = http.request

        def request(method, url, **kw):
            status, body = orig(method, url, **kw)
            if method == "POST" and url.endswith("/admin/realms/acme-finance/users"):
                http.routes.insert(0, ("GET", "/admin/realms/acme-finance/users?exact=true", 200, [{"id": "u1"}]))
            return status, body
        http.request = request  # type: ignore[method-assign]
        for name in ["terraform", "database", "realm", "credentials", "control-plane"]:
            STEP_FUNCS[name](ctx)

        tfvars = json.loads((root / "infra/terraform/envs/sandbox/tenants.auto.tfvars.json").read_text())
        check(set(tfvars["tenants"]) == {"acme-finance", "dedi-co"}, "S1 tfvars generated from spec files")
        argvs = [" ".join(c[0]) for c in shell.calls]
        check(any("apply -input=false tenant.tfplan" in a for a in argvs), "S2 terraform applies the reviewed plan file")
        psql = next(c for c in shell.calls if c[0][0] == "psql")
        check("Tenant-PW-1" not in " ".join(psql[0]) and psql[2]["TENANT_DB_PASSWORD"] == "Tenant-PW-1"
              and psql[2]["PGSSLMODE"] == "verify-full", "S3 DB password via environment, TLS verify-full")
        check("CREATE DATABASE %I OWNER %I" in (psql[1] or "") and "WHERE NOT EXISTS" in (psql[1] or ""),
              "S4 database SQL is idempotent")
        check(all("rotated-secret" not in a and "Master-PW-1" not in a for a in argvs), "S5 no secret in any argv")
        stored = [c for c in shell.calls if "create-secret" in c[0]]
        check(len(stored) == 2 and "rotated-secret" in (stored[0][1] or ""), "S6 client secret stored via stdin")
        check("acme.admin" in (stored[1][1] or "") and "--kms-key-id" in stored[1][0],
              "S7 temporary admin password stored with the tenant key")
        realm_post = next(c for c in http.calls if c[0] == "POST" and c[1].endswith("/admin/realms"))
        realm = realm_post[2]
        check(realm["realm"] == "acme-finance" and not any(u["username"] in ("maker", "admin") for u in realm.get("users", [])),
              "S8 realm rendered for the tenant without demo users")
        check(not any(c.get("directAccessGrantsEnabled") for c in realm["clients"]) and "console-dev" not in json.dumps(realm)
              and not any(u["username"].startswith("dev-") for u in realm.get("users", [])),
              "S8b realm has no direct-grant client, no console-dev and no dev users")
        user_post = next(c for c in http.calls if c[0] == "POST" and c[1].endswith("/users"))
        check(user_post[2]["requiredActions"] == ["UPDATE_PASSWORD", "CONFIGURE_TOTP"] and user_post[2]["username"] == "acme.admin",
              "S9 admin must change password and enrol TOTP")
        helm = next(a for a in argvs if a.startswith("helm upgrade"))
        check('["acme-finance", "demo-nbfc"]' in helm and "--reuse-values" in helm, "S10 tenant added to Helm values")
        check(argvs.index(next(a for a in argvs if "wait --for=condition=Ready" in a))
              < argvs.index(next(a for a in argvs if "rollout restart" in a)), "S11 pods restart only after the secret synced")
        prov = next(c for c in http.calls if c[0] == "POST" and c[1].endswith("/platform/v1/tenants"))
        check(prov[2]["adminUsers"] == ["acme.admin"] and prov[2]["kmsKeyArn"].startswith("arn:aws:kms")
              and prov[2]["dbSecretArn"].endswith(":db"), "S12 provisioning request carries admins, key and secret")

        # Re-run: everything exists -> no new realm, no new users, no second provisioning call.
        http2 = FakeHttp([
            ("POST", "/protocol/openid-connect/token", 200, {"access_token": "kc-token"}),
            ("GET", "/admin/realms/acme-finance/roles/TENANT_ADMIN", 200, role),
            ("GET", "/admin/realms/acme-finance/users?exact=true", 200, [{"id": "u1"}]),
            ("GET", "/admin/realms/acme-finance", 200, {"realm": "acme-finance"}),
            ("GET", "/platform/v1/tenants", 200, [{"code": "acme-finance", "status": "ACTIVE", "modules": "CORE"}]),
        ])
        shell2 = FakeShell({"output -json tenants": outputs, "describe-secret": "",
                            "helm get values": json.dumps({"tenantDbSecrets": {"enabled": True, "tenants": ["acme-finance"]}}),
                            "jsonpath={.data}": '{"COREBANKING_TENANT_ACME_FINANCE_URL":"x"}'})
        ctx2 = Context(spec, shell2, http2, env, repo=root)
        for name in ["realm", "credentials", "control-plane", "verify"]:
            STEP_FUNCS[name](ctx2)
        check(not any(c[0] == "POST" and ("/users" in c[1] or c[1].endswith("/admin/realms") or "/platform/v1/tenants" in c[1])
                      for c in http2.calls), "R1 re-run creates nothing twice")
        check(not any(" ".join(c[0]).startswith("helm upgrade") for c in shell2.calls), "R2 re-run leaves Helm values alone")

        stand = load_spec(root / "infra/tenants/sandbox/bank-one.json")
        out: list[str] = []
        import builtins
        orig_print = builtins.print
        builtins.print = lambda *a, **k: out.append(" ".join(map(str, a)))  # type: ignore[assignment]
        try:
            ctx3 = Context(stand, FakeShell(), FakeHttp([]), env, repo=root)
            try:
                run(ctx3, "terraform")
            except ProvisioningError:
                pass
        finally:
            builtins.print = orig_print
        check("[skip] terraform (STANDALONE)" in out and "[skip] database (STANDALONE)" in out,
              "A1 STANDALONE skips cloud steps")
        check(redact('{"password": "abc", "token=xyz"}').count("***") == 2, "A2 secrets redacted from error text")

    print(f"{'ALL' if failures == 0 else failures} {'PASSED' if failures == 0 else 'FAILED'}")
    return 1 if failures else 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("spec", nargs="?", type=Path)
    ap.add_argument("--from-step", choices=STEPS, default="validate")
    ap.add_argument("--plan-only", action="store_true", help="stop after the Terraform plan")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args(argv)
    if a.self_test:
        return self_test()
    if not a.spec:
        ap.error("spec is required")
    try:
        spec = load_spec(a.spec.resolve())
        run(Context(spec, Shell(), Http(), dict(os.environ), plan_only=a.plan_only), a.from_step)
        return 0
    except ProvisioningError as e:
        print(f"PROVISIONING FAILED: {e}", file=sys.stderr)
        print("Fix the cause and re-run; completed steps are skipped or repeated safely.", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
