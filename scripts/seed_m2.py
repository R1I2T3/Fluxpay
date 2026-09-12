#!/usr/bin/env python3
"""Idempotently fund an existing FluxPay demo user through the wallet API."""

import argparse
import json
import os
import sys
import urllib.request
import uuid


FUNDING = (("USD", "500.0000"), ("INR", "10000.0000"), ("EUR", "50.0000"))


def api_request(base_url, path, token, body=None, idempotency_key=None):
    headers = {"Accept": "application/json", "Authorization": f"Bearer {token}"}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if idempotency_key is not None:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(base_url.rstrip("/") + path, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=5) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if not isinstance(payload, dict) or "data" not in payload:
        raise ValueError("backend returned an invalid API envelope")
    return payload["data"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-id", default=os.environ.get("M2_USER_ID"))
    parser.add_argument(
        "--base-url",
        default=os.environ.get("M2_BASE_URL", os.environ.get("SEED_BASE_URL", "http://localhost:8080")),
    )
    args = parser.parse_args()

    token = os.environ.get("M2_BEARER_TOKEN", "").strip()
    if not token:
        print("seed failed: M2_BEARER_TOKEN is required")
        return 2
    if not args.user_id:
        print("seed failed: --user-id or M2_USER_ID is required")
        return 2
    try:
        user_id = str(uuid.UUID(args.user_id))
    except (ValueError, AttributeError):
        print("seed failed: user ID must be a UUID")
        return 2

    try:
        for currency, amount in FUNDING:
            api_request(
                args.base_url,
                "/api/wallets/receive-demo",
                token,
                {"currency": currency, "amount": amount},
                f"seed-m2:{user_id}:{currency}:v1",
            )
            print(f"{currency} seed request OK")

        wallets = api_request(args.base_url, "/api/wallets", token)
        if not isinstance(wallets, list):
            raise ValueError("wallet list is invalid")
        for wallet in wallets:
            print(f"{wallet['currency']} available={wallet['availableBalance']} held={wallet['heldBalance']}")
        return 0
    except (OSError, ValueError, KeyError) as exception:
        print(f"seed failed: {type(exception).__name__}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
