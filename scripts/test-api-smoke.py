#!/usr/bin/env python3
"""Exercise every OpenAPI operation exposed by the local backend."""

import base64
import hashlib
import hmac
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid


BASE_URL = os.environ.get("API_BASE_URL", "http://localhost:8080")


def base64url(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def token(user_id):
    now = int(time.time())
    header = base64url(json.dumps({"alg": "HS256", "typ": "JWT"}, separators=(",", ":")).encode())
    payload = base64url(
        json.dumps(
            {"sub": str(user_id), "email": "api-smoke@local.test", "role": "CUSTOMER", "iat": now, "exp": now + 3600},
            separators=(",", ":"),
        ).encode()
    )
    unsigned = f"{header}.{payload}"
    secret = os.environ.get("JWT_SECRET", "change-me-32-bytes-minimum-0123456789abcdef").encode()
    signature = base64url(hmac.new(secret, unsigned.encode(), hashlib.sha256).digest())
    return f"{unsigned}.{signature}"


def call(name, method, path, auth_token, body=None, headers=None):
    request_headers = {"X-Local-User-Id": auth_token}
    request_headers.update(headers or {})
    data = json.dumps(body) if body is not None else None
    if data is not None:
        request_headers["Content-Type"] = "application/json"
    command = ["curl.exe", "-sS", "-X", method, "-w", "\n%{http_code}"]
    for key, value in request_headers.items():
        command.extend(["-H", f"{key}: {value}"])
    if data is not None:
        command.extend(["--data", data])
    command.append(BASE_URL + path)
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError(f"FAIL {name}: curl exited {result.returncode}: {result.stderr}")
    content, status = result.stdout.rsplit("\n", 1)
    if not status.startswith("2"):
        raise RuntimeError(f"FAIL {name}: {status} {content}")
    print(f"PASS {name}: {status}")
    return json.loads(content)


def main():
    # The source checkout has no authentication controller; local profile accepts
    # this test-only identity header in JwtAuthFilter.
    auth_token = "11111111-1111-1111-1111-111111111111"
    recipient = call(
        "recipient.create",
        "POST",
        "/api/recipients",
        auth_token,
        {"name": "API Smoke Recipient", "account": f"acct-{uuid.uuid4().hex[:12]}", "bankName": "Test Bank", "country": "IN", "currency": "INR", "status": "ACTIVE"},
    )["data"]
    recipient_id = recipient["id"]
    call("recipient.list", "GET", "/api/recipients", auth_token)
    call(
        "recipient.update",
        "PUT",
        f"/api/recipients/{recipient_id}",
        auth_token,
        {"name": "API Smoke Recipient Updated", "account": recipient["account"], "bankName": "Test Bank", "country": "IN", "currency": "INR", "status": "ACTIVE", "expectedVersion": 0},
    )
    draft_body = {
        "sourceWalletId": str(uuid.uuid4()),
        "recipientId": recipient_id,
        "sourceAmount": 100,
        "sourceCurrency": "USD",
        "payoutCurrency": "INR",
        "purpose": "FAMILY_SUPPORT",
        "preference": "CHEAPEST",
    }
    draft = call("payment.draft", "POST", "/api/payments/draft", auth_token, draft_body, {"Idempotency-Key": str(uuid.uuid4())})["data"]
    payment_id = draft["id"]
    quotes = call("payment.quote", "POST", f"/api/payments/{payment_id}/quotes", auth_token)["data"]
    call("payment.quotes.get", "GET", f"/api/payments/{payment_id}/quotes", auth_token)
    call(
        "payment.confirm",
        "POST",
        f"/api/payments/{payment_id}/confirm",
        auth_token,
        {"quoteId": quotes["recommendedQuoteId"]},
        {"Idempotency-Key": str(uuid.uuid4())},
    )
    call("payment.detail", "GET", f"/api/payments/{payment_id}", auth_token)
    call("payment.list", "GET", "/api/payments?page=0&size=20", auth_token)
    cancellable = call("payment.draft.cancel-case", "POST", "/api/payments/draft", auth_token, draft_body, {"Idempotency-Key": str(uuid.uuid4())})["data"]
    call("payment.cancel", "POST", f"/api/payments/{cancellable['id']}/cancel", auth_token)
    print("PASS all OpenAPI operations")


if __name__ == "__main__":
    main()
