#!/usr/bin/env python3
"""Sample receiver-side check of a CoreBanking webhook signature (US-121). Python standard library only.

    X-CoreBanking-Signature: t=<unix seconds>,v1=<key id>:<hex HMAC-SHA256>[,v1=<key id>:<hex>]

The signed text is t + "." + the raw request body. Verify BEFORE parsing the body, and compare in constant time.

Usage:  verify_webhook.py <signature header> <key id> <secret> < body
Exit status 0 when the signature is valid and fresh, 1 otherwise.
"""
import hashlib
import hmac
import sys
import time

TOLERANCE_SECONDS = 300


def verify(header: str, body: bytes, secrets: dict, now: float, tolerance: int = TOLERANCE_SECONDS) -> bool:
    timestamp = None
    signatures = []
    for part in header.split(","):
        part = part.strip()
        if part.startswith("t=") and part[2:].isdigit():
            timestamp = int(part[2:])
        elif part.startswith("v1=") and ":" in part:
            key_id, _, signature = part[3:].partition(":")
            signatures.append((key_id, signature))
    if timestamp is None or not signatures:
        return False
    if abs(now - timestamp) > tolerance:
        return False          # too old (a replay) or too far in the future
    signed = str(timestamp).encode() + b"." + body
    for key_id, signature in signatures:
        secret = secrets.get(key_id)
        if secret is None:
            continue
        expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
        if hmac.compare_digest(expected, signature):
            return True
    return False


def self_test() -> None:
    # the vector of WebhookSignatureTest (integration-core): fake secret, fixed time
    signing = "CLAUDE-TEST-secret-0123456789abcd"
    body = b'{"id":"evt_1","type":"loan.disbursed"}'
    header = "t=1790000000,v1=k1:70191006db4c9a5a3af9d8db80e8f2575170add7745a62480cd4799a280ffc24"
    assert verify(header, body, {"k1": signing}, 1790000010)
    assert not verify(header, body + b" ", {"k1": signing}, 1790000010)
    assert not verify(header, body, {"k1": signing}, 1790000301)
    assert not verify(header, body, {"k2": signing}, 1790000010)
    print("self-test passed")


if __name__ == "__main__":
    if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
        self_test()
        sys.exit(0)
    if len(sys.argv) != 4:
        print(__doc__)
        sys.exit(2)
    ok = verify(sys.argv[1], sys.stdin.buffer.read(), {sys.argv[2]: sys.argv[3]}, time.time())
    print("valid" if ok else "INVALID")
    sys.exit(0 if ok else 1)
