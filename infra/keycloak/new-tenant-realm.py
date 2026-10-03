#!/usr/bin/env python3
"""Render a Keycloak realm for a new tenant from the demo-nbfc template (ADR-007: one realm per tenant).

The template (deploy/local/keycloak/demo-nbfc-realm.json) is the single source of truth for the
security baseline: password policy, brute-force lockout, TOTP policy, session/token lifetimes,
permission roles and the token mappers the backend relies on (`tenant`, `permissions`, aud=api).
This script changes only tenant-specific values:

  * realm name and display name
  * the hardcoded `tenant` claim on every client (TenantFilter routes requests by this claim)
  * the console client's root/redirect/post-logout URLs
  * the batch service client secret -> placeholder (set the real secret via the admin API / Secrets Manager)
  * demo users are REMOVED unless --keep-demo-users is given (they are for local development only)
  * the `console-dev` client (LOCAL ONLY direct username/password grant behind the console's development
    sign-in) and its scope mappings are ALWAYS removed, even with --keep-demo-users, unless --keep-dev-login is
    given explicitly; the dev-* users follow --keep-demo-users. A tenant realm must never accept the password grant.

Usage:
  new-tenant-realm.py --tenant acme-finance --console-url https://acme-finance.console.example.in \
      --display-name "Acme Finance Ltd" --out acme-finance-realm.json
  new-tenant-realm.py --self-test

Standard library only (Python 3.9+).
"""
from __future__ import annotations

import argparse
import copy
import json
import re
import sys
import tempfile
from pathlib import Path
from urllib.parse import urlparse

# Must match TenantContext.set() and the control.tenant.code CHECK constraint.
TENANT_CODE = re.compile(r"^[a-z][a-z0-9-]{2,30}$")
REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_TEMPLATE = REPO_ROOT / "deploy" / "local" / "keycloak" / "demo-nbfc-realm.json"
TEMPLATE_TENANT = "demo-nbfc"
CONSOLE_CLIENT = "console"
SERVICE_CLIENT = "corebanking-service"
DEV_LOGIN_CLIENT = "console-dev"
DEV_USER_PREFIX = "dev-"
SECRET_PLACEHOLDER = "change-me"


def render(template: dict, tenant: str, console_url: str, display_name: str | None,
           keep_demo_users: bool, keep_dev_login: bool = False) -> dict:
    if not TENANT_CODE.match(tenant):
        raise ValueError(f"invalid tenant code {tenant!r}: must match {TENANT_CODE.pattern}")
    parsed = urlparse(console_url)
    if parsed.scheme not in ("https", "http") or not parsed.netloc:
        raise ValueError(f"invalid console URL {console_url!r}")
    if parsed.scheme == "http" and parsed.hostname not in ("localhost", "127.0.0.1"):
        raise ValueError("console URL must be https outside localhost")
    base = console_url.rstrip("/")

    realm = copy.deepcopy(template)
    realm["realm"] = tenant
    realm["displayName"] = display_name or tenant
    realm.pop("id", None)

    if not keep_dev_login:
        # ADR-007 amendment: the direct-grant client exists for the local stack only.
        realm["clients"] = [c for c in realm.get("clients", []) if c.get("clientId") != DEV_LOGIN_CLIENT]
        if "scopeMappings" in realm:
            realm["scopeMappings"] = [m for m in realm["scopeMappings"] if m.get("client") != DEV_LOGIN_CLIENT]
        for owner, mappings in list(realm.get("clientScopeMappings", {}).items()):
            if owner == DEV_LOGIN_CLIENT:
                del realm["clientScopeMappings"][owner]
            else:
                realm["clientScopeMappings"][owner] = [m for m in mappings if m.get("client") != DEV_LOGIN_CLIENT]
        direct = [c.get("clientId") for c in realm["clients"] if c.get("directAccessGrantsEnabled")]
        if direct:
            raise ValueError(f"template enables the password grant on {direct}; refusing to render a tenant realm")

    tenant_mappers = 0
    for client in realm.get("clients", []):
        client.pop("id", None)
        for mapper in client.get("protocolMappers", []):
            mapper.pop("id", None)
            cfg = mapper.get("config", {})
            if mapper.get("protocolMapper") == "oidc-hardcoded-claim-mapper" and cfg.get("claim.name") == "tenant":
                cfg["claim.value"] = tenant
                tenant_mappers += 1
        if client.get("clientId") == CONSOLE_CLIENT:
            client["rootUrl"] = base
            client["redirectUris"] = [f"{base}/*"]
            client["webOrigins"] = ["+"]
            client.setdefault("attributes", {})["post.logout.redirect.uris"] = f"{base}/*"
        if client.get("clientId") == SERVICE_CLIENT:
            client["secret"] = SECRET_PLACEHOLDER
    if tenant_mappers == 0:
        raise ValueError("template has no hardcoded 'tenant' claim mapper; refusing to render")

    users = realm.get("users", [])
    if keep_demo_users:
        for user in users:
            if user.get("email", "").endswith(f"@{TEMPLATE_TENANT}.example.invalid"):
                user["email"] = f"{user['username']}@{tenant}.example.invalid"
    else:
        realm["users"] = [u for u in users if u.get("serviceAccountClientId")]
    return realm


def self_test(template_path: Path) -> None:
    template = json.loads(template_path.read_text(encoding="utf-8"))
    out = render(template, "acme-finance", "https://acme-finance.console.example.in/", "Acme Finance", False)
    text = json.dumps(out)
    assert out["realm"] == "acme-finance"
    assert TEMPLATE_TENANT not in text, "template tenant code leaked into rendered realm"
    console = next(c for c in out["clients"] if c["clientId"] == CONSOLE_CLIENT)
    assert console["redirectUris"] == ["https://acme-finance.console.example.in/*"]
    assert console["attributes"]["pkce.code.challenge.method"] == "S256"
    claims = [m["config"]["claim.value"] for c in out["clients"] for m in c.get("protocolMappers", [])
              if m["config"].get("claim.name") == "tenant"]
    assert claims and all(v == "acme-finance" for v in claims)
    assert all(u.get("serviceAccountClientId") for u in out["users"]), "demo users must be stripped"
    assert any(c["clientId"] == DEV_LOGIN_CLIENT and c["directAccessGrantsEnabled"] for c in template["clients"]), \
        "template should carry the local development sign-in client (otherwise this test proves nothing)"

    def assert_no_dev_login(realm: dict, label: str) -> None:
        assert not any(c.get("directAccessGrantsEnabled") for c in realm["clients"]), f"{label}: direct-grant client"
        assert DEV_LOGIN_CLIENT not in json.dumps(realm), f"{label}: {DEV_LOGIN_CLIENT} leaked into rendered realm"

    assert_no_dev_login(out, "tenant realm")
    assert not any(u["username"].startswith(DEV_USER_PREFIX) for u in out["users"]), "dev users must be stripped"
    assert out["passwordPolicy"] == template["passwordPolicy"]
    assert out["bruteForceProtected"] is True and out["failureFactor"] == 5
    kept = render(template, "acme-finance", "http://localhost:5173", None, True)
    assert any(u["username"] == "maker" for u in kept["users"])
    assert_no_dev_login(kept, "--keep-demo-users")
    local = render(template, "acme-finance", "http://localhost:5173", None, True, keep_dev_login=True)
    dev = next(c for c in local["clients"] if c["clientId"] == DEV_LOGIN_CLIENT)
    assert dev["directAccessGrantsEnabled"] and any(u["username"] == "dev-maker" for u in local["users"])
    only_client = render(template, "acme-finance", "http://localhost:5173", None, False, keep_dev_login=True)
    assert not any(u["username"].startswith(DEV_USER_PREFIX) for u in only_client["users"])
    for bad in ("Acme", "1abc", "ab", "a" * 40, "acme_finance"):
        try:
            render(template, bad, "https://x.example.in", None, False)
        except ValueError:
            continue
        raise AssertionError(f"tenant code {bad!r} should be rejected")
    try:
        render(template, "acme-finance", "http://acme.example.in", None, False)
        raise AssertionError("plain http outside localhost should be rejected")
    except ValueError:
        pass
    with tempfile.TemporaryDirectory() as tmp:
        p = Path(tmp) / "r.json"
        p.write_text(json.dumps(out, indent=2), encoding="utf-8")
        json.loads(p.read_text(encoding="utf-8"))
    print("self-test passed")


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tenant", help="tenant code, e.g. acme-finance (becomes the realm name)")
    ap.add_argument("--console-url", help="staff console base URL for this tenant")
    ap.add_argument("--display-name", help="realm display name (tenant legal name)")
    ap.add_argument("--template", type=Path, default=DEFAULT_TEMPLATE)
    ap.add_argument("--out", type=Path, help="output file (default: stdout)")
    ap.add_argument("--keep-demo-users", action="store_true", help="keep demo users (local development only)")
    ap.add_argument("--keep-dev-login", action="store_true",
                    help=f"keep the '{DEV_LOGIN_CLIENT}' direct-grant client (LOCAL DEVELOPMENT ONLY; never for a tenant)")
    ap.add_argument("--self-test", action="store_true", help="run built-in checks against the template")
    args = ap.parse_args(argv)

    if args.self_test:
        self_test(args.template)
        return 0
    if not args.tenant or not args.console_url:
        ap.error("--tenant and --console-url are required")

    template = json.loads(args.template.read_text(encoding="utf-8"))
    try:
        realm = render(template, args.tenant, args.console_url, args.display_name, args.keep_demo_users,
                       keep_dev_login=args.keep_dev_login)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    text = json.dumps(realm, indent=2, ensure_ascii=False) + "\n"
    if args.out:
        args.out.write_text(text, encoding="utf-8")
        print(f"wrote {args.out}", file=sys.stderr)
    else:
        sys.stdout.write(text)
    if args.keep_dev_login:
        print(f"WARNING: '{DEV_LOGIN_CLIENT}' (password grant) kept — this realm is for local development only",
              file=sys.stderr)
    print(f"reminder: rotate the '{SERVICE_CLIENT}' client secret after import and store it in "
          "Secrets Manager / the tenant's K8s Secret", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
