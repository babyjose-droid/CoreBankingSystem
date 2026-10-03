#!/usr/bin/env python3
"""Contract test of the LOS flow (US-124) against a running local stack. Python standard library only.

It plays the pilot LOS: customer create-or-get, preview, loan, KFS acceptance, beneficiary, mandate, straight-through
disbursement, payout status, a collection order paid through the SIMULATOR, and the reconciliation queries.

NOT RUN in the build environment (no stack was available there). Expect to adjust it on first use.

Prerequisites
  - the local stack of docs/runbooks/local-dev.md, with COREBANKING_INTEGRATION_PROVIDERS=SIMULATOR;
  - SIMULATOR approved as the PAYOUT, COLLECTION and MANDATE provider, each with a webhookSecret
    (docs/runbooks/integrations.md, "Configure a provider");
  - an ACTIVE loan product, and an API client (or the local service client) whose token carries:
    customer:view customer:create product:view loan:view loan:create loan:stp payout:view payout:beneficiary
    mandate:register mandate:view collection:create collection:view integration:simulate
  - the customer of step 1 must exist or be approved by a checker (new customers go through maker-checker).

Environment
  CB_API            default http://localhost:8080
  CB_TOKEN_URL      default http://localhost:8081/realms/demo-nbfc/protocol/openid-connect/token
  CB_CLIENT_ID, CB_CLIENT_SECRET   the API client
  CB_PRODUCT        loan product code (default PL01)
  CB_BRANCH         home branch for a new customer (default HO)
  CB_RUN            a suffix that makes this run's external references unique (default: the time)

All data it creates is fake and marked CLAUDE-TEST.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = os.environ.get("CB_API", "http://localhost:8080")
TOKEN_URL = os.environ.get("CB_TOKEN_URL", "http://localhost:8081/realms/demo-nbfc/protocol/openid-connect/token")
RUN = os.environ.get("CB_RUN", str(int(time.time())))
PRODUCT = os.environ.get("CB_PRODUCT", "PL01")
BRANCH = os.environ.get("CB_BRANCH", "HO")
_token = {"value": None, "until": 0}


def token() -> str:
    if _token["value"] and time.time() < _token["until"]:
        return _token["value"]
    data = urllib.parse.urlencode({"grant_type": "client_credentials", "client_id": os.environ["CB_CLIENT_ID"],
                                   "client_secret": os.environ["CB_CLIENT_SECRET"]}).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN_URL, data=data), timeout=20) as r:
        t = json.load(r)
    _token["value"], _token["until"] = t["access_token"], time.time() + int(t.get("expires_in", 60)) - 15
    return _token["value"]


def call(method: str, path: str, body=None, headers=None, expect=(200, 201, 202)):
    h = {"Authorization": "Bearer " + token(), "Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    req = urllib.request.Request(API + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            status, text = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode()
    payload = json.loads(text) if text else None
    if status not in expect:
        raise SystemExit(f"FAIL {method} {path}: {status} {text}")
    return status, payload


def step(label: str) -> None:
    print("==", label)


def wait_for(label: str, probe, seconds: int = 60):
    deadline = time.time() + seconds
    while time.time() < deadline:
        value = probe()
        if value:
            return value
        time.sleep(2)
    raise SystemExit("FAIL timed out waiting for " + label)


def main() -> None:
    step("1 customer create-or-get by externalRef")
    ext = "CLAUDE-TEST-LOS-" + RUN
    _, found = call("GET", "/api/v1/customers?externalRef=" + urllib.parse.quote(ext))
    if not found:
        status, created = call("POST", "/api/v1/customers", {
            "customerType": "INDIVIDUAL", "firstName": "CLAUDE-TEST", "lastName": "Borrower " + RUN[-4:],
            "dateOfBirth": "1990-05-15", "gender": "FEMALE", "mobile": "90000" + RUN[-5:].rjust(5, "0"),
            "homeBranch": BRANCH, "externalRef": ext,
            "address": {"line1": "1 CLAUDE-TEST Street", "city": "Kochi", "stateCode": "32", "pincode": "682001"}},
            headers={"Idempotency-Key": "cust-" + ext})
        if status == 202:
            print("   approval", created["id"], "- waiting for a checker to approve the customer")
            found = wait_for("customer approval", lambda: call("GET", "/api/v1/customers?externalRef=" + urllib.parse.quote(ext))[1], 300)
        else:
            found = [created]
    customer = found[0]
    # idempotency: the same externalRef answers with the same customer
    status, again = call("POST", "/api/v1/customers", {
        "customerType": "INDIVIDUAL", "firstName": "CLAUDE-TEST", "lastName": "Borrower " + RUN[-4:], "dateOfBirth": "1990-05-15",
        "mobile": "90000" + RUN[-5:].rjust(5, "0"), "homeBranch": BRANCH, "externalRef": ext})
    assert status == 200 and again["id"] == customer["id"], "create-or-get must return the existing customer"

    step("2 preview and 3 create loan (idempotent on externalRef)")
    application = {"productCode": PRODUCT, "customerId": customer["id"], "amount": "100000", "tenorMonths": 12,
                   "externalRef": "CLAUDE-TEST-APP-" + RUN}
    _, preview = call("POST", "/api/v1/loans/preview", application)
    assert preview["netDisbursal"], "preview shows the net disbursal"
    _, loan = call("POST", "/api/v1/loans", application)
    _, loan2 = call("POST", "/api/v1/loans", application)
    assert loan["id"] == loan2["id"] and loan["status"] == "SANCTIONED"
    lid = loan["id"]

    step("4 KFS acceptance")
    call("GET", f"/api/v1/loans/{lid}/kfs")
    call("POST", f"/api/v1/loans/{lid}/kfs-acceptance", {"channel": "API", "evidenceRef": "CLAUDE-TEST-otp-" + RUN})

    step("5 beneficiary: an invalid account is refused, a valid one is stored masked")
    call("PUT", f"/api/v1/loans/{lid}/payout-beneficiary",
         {"holderName": "CLAUDE-TEST Borrower", "accountNumber": "000000CLAUDE0000", "ifsc": "TEST0000001"}, expect=(422,))
    _, ben = call("PUT", f"/api/v1/loans/{lid}/payout-beneficiary",
                  {"holderName": "CLAUDE-TEST Borrower", "accountNumber": "000000CLAUDE1234", "ifsc": "TEST0000001"})
    assert ben["accountMasked"].endswith("1234") and "CLAUDE1234" not in json.dumps(ben), "account numbers are masked"

    step("6 mandate registration and status")
    _, mandate = call("POST", f"/api/v1/loans/{lid}/mandates", {
        "holderName": "CLAUDE-TEST Borrower", "accountNumber": "000000CLAUDE1234", "ifsc": "TEST0000001", "maxAmount": "20000",
        "frequency": "MONTHLY", "startDate": time.strftime("%Y-%m-%d"), "sponsorBankCode": "TESTBANK", "utilityCode": "TESTUTIL01"})
    assert "CLAUDE1234" not in json.dumps(mandate)
    active = wait_for("mandate ACTIVE", lambda: [m for m in call("GET", f"/api/v1/loans/{lid}/mandates")[1] if m["status"] == "ACTIVE"], 600)
    assert active[0]["umrn"]

    step("7 straight-through disbursement")
    status, disbursed = call("POST", f"/api/v1/loans/{lid}/disbursement", {})
    assert status == 200 and disbursed["status"] == "ACTIVE", "loan:stp disburses at once"
    call("POST", f"/api/v1/loans/{lid}/disbursement", {}, expect=(409,))

    step("8 payout reaches SUCCESS with a UTR")
    paid = wait_for("payout SUCCESS", lambda: [p for p in call("GET", f"/api/v1/payouts?loanId={lid}")[1] if p["status"] == "SUCCESS"])
    assert paid[0]["utr"] and paid[0]["amount"] and paid[0]["beneficiaryAccountMasked"].endswith("1234")

    step("9 collection order, paid by a simulated signed callback; a replayed callback posts once")
    _, order = call("POST", f"/api/v1/loans/{lid}/collection-orders", {"amount": "1500.00", "methods": ["UPI"]},
                    headers={"Idempotency-Key": "order-" + RUN})
    _, order2 = call("POST", f"/api/v1/loans/{lid}/collection-orders", {"amount": "1500.00", "methods": ["UPI"]},
                     headers={"Idempotency-Key": "order-" + RUN})
    assert order["id"] == order2["id"] and order["paymentUrl"]
    event = {"kind": "collection", "reference": order["reference"], "status": "PAID", "amount": "1500.00", "eventId": "CLAUDE-TEST-evt-" + RUN}
    _, first = call("POST", "/api/v1/integrations/simulator/callbacks", event)
    _, second = call("POST", "/api/v1/integrations/simulator/callbacks", event)
    assert first["receipt"] == "STORED" and second["receipt"] == "DUPLICATE", "replay protection on the provider event id"
    wait_for("order PAID", lambda: call("GET", f"/api/v1/collection-orders/{order['id']}")[1]["status"] == "PAID")
    receipts = wait_for("repayment posted", lambda: [t for t in call("GET", f"/api/v1/loans/{lid}/transactions")[1] if t["type"] == "REPAYMENT"])
    assert len(receipts) == 1 and float(receipts[0]["amount"]) == 1500.0, "the payment is posted exactly once"

    step("10 reconciliation: nothing of this loan is unposted")
    _, unmatched = call("GET", "/api/v1/gateway-payments/reconciliation?category=PAYMENT_NOT_POSTED")
    assert not [r for r in unmatched if r.get("loanId") == lid]

    step("11 an unsigned provider callback is refused")
    tenant = TOKEN_URL.split("/realms/")[1].split("/")[0]
    req = urllib.request.Request(f"{API}/hooks/v1/{tenant}/collection/SIMULATOR", data=json.dumps(event).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    try:
        urllib.request.urlopen(req, timeout=20)
        raise SystemExit("FAIL an unsigned callback was accepted")
    except urllib.error.HTTPError as e:
        assert e.code == 401, e.code

    print("LOS CONTRACT TEST PASSED for loan", loan["loanNo"])


if __name__ == "__main__":
    if "CB_CLIENT_ID" not in os.environ or "CB_CLIENT_SECRET" not in os.environ:
        print(__doc__)
        sys.exit(2)
    main()
