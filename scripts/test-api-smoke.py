#!/usr/bin/env python3
"""Exercise every OpenAPI operation exposed by the local backend."""

import base64
import hashlib
import hmac
import json
import os
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path


BASE_URL = os.environ.get("API_BASE_URL", "http://localhost:8080")
SMOKE_USER_ID = "11111111-1111-1111-1111-111111111111"
SMOKE_WALLET_ID = "22222222-2222-2222-2222-222222222222"


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
    data = json.dumps(body).encode() if body is not None else None
    if data is not None:
        request_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE_URL + path, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            status = resp.status
            content = resp.read().decode()
    except urllib.error.HTTPError as e:
        content = e.read().decode()
        raise RuntimeError(f"FAIL {name}: {e.code} {content}")
    if not str(status).startswith("2"):
        raise RuntimeError(f"FAIL {name}: {status} {content}")
    print(f"PASS {name}: {status}")
    return json.loads(content) if content else {}


def main():
    import subprocess

    project_root = Path(__file__).resolve().parent.parent
    helper_env = dict(os.environ)
    env_file = project_root / ".env"
    if env_file.exists():
        with open(env_file, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    helper_env.setdefault(key, value)
    jdbc_jar = (
        Path.home()
        / ".m2"
        / "repository"
        / "com"
        / "oracle"
        / "database"
        / "jdbc"
        / "ojdbc11"
        / "23.4.0.24.05"
        / "ojdbc11-23.4.0.24.05.jar"
    )
    helper = project_root / "scripts" / "_ensure_api_smoke_user.java"
    subprocess.run(["javac", str(helper)], check=True)
    subprocess.run(
        ["java", "-cp", os.pathsep.join([str(helper.parent), str(jdbc_jar)]), "_ensure_api_smoke_user"],
        check=True,
        env=helper_env,
    )
    # The source checkout has no authentication controller; local profile accepts
    # this test-only identity header in JwtAuthFilter.
    auth_token = SMOKE_USER_ID
    recipient = call(
        "recipient.create",
        "POST",
        "/api/recipients",
        auth_token,
        {
            "name": "API Smoke Recipient",
            "account": f"acct-{uuid.uuid4().hex[:12]}",
            "bankName": "Test Bank",
            "country": "IN",
            "currency": "INR",
            "status": "ACTIVE",
        },
    )["data"]
    recipient_id = recipient["id"]
    call("recipient.list", "GET", "/api/recipients", auth_token)
    call(
        "recipient.update",
        "PUT",
        f"/api/recipients/{recipient_id}",
        auth_token,
        {
            "name": "API Smoke Recipient Updated",
            "account": recipient["account"],
            "bankName": "Test Bank",
            "country": "IN",
            "currency": "INR",
            "status": "ACTIVE",
            "expectedVersion": 0,
        },
    )
    draft_body = {
        "sourceWalletId": SMOKE_WALLET_ID,
        "recipientId": recipient_id,
        "sourceAmount": "100.0000",
        "sourceCurrency": "USD",
        "payoutCurrency": "INR",
        "purpose": "FAMILY_SUPPORT",
        "preference": "CHEAPEST",
    }
    draft = call(
        "payment.draft", "POST", "/api/payments/draft", auth_token, draft_body, {"Idempotency-Key": str(uuid.uuid4())}
    )["data"]
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
    cancellable = call(
        "payment.draft.cancel-case",
        "POST",
        "/api/payments/draft",
        auth_token,
        draft_body,
        {"Idempotency-Key": str(uuid.uuid4())},
    )["data"]
    call("payment.cancel", "POST", f"/api/payments/{cancellable['id']}/cancel", auth_token)
    print("PASS all OpenAPI operations")


if __name__ == "__main__":
    main()
