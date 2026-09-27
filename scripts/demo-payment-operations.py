#!/usr/bin/env python3
"""Create one real payment for each local payment-operations demo scenario."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from decimal import Decimal, InvalidOperation

from platform_commands import load_env

SCENARIOS = {
    "retry-success": "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE",
    "refund-exhaustion": "FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE",
}
FAILURE_ATTEMPTS_ENV = {
    "retry-success": "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS",
    "refund-exhaustion": "FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS",
}
DEFAULT_FAILURE_ATTEMPTS = {
    "retry-success": 2,
    "refund-exhaustion": 6,
}
SOURCE_AMOUNT = "10.0000"
REQUEST_TIMEOUT = 10


def _redact(value, *secrets):
    text = str(value)
    for secret in secrets:
        if secret:
            text = text.replace(str(secret), "[REDACTED]")
    return text


def _error_fields(payload, status):
    code = f"HTTP_{status}"
    message = "backend returned an error"
    if isinstance(payload, dict):
        error = payload.get("error")
        if isinstance(error, dict):
            code = error.get("code", code)
            message = error.get("message", message)
        else:
            code = payload.get("code", code)
            message = payload.get("message", message)
    return str(code), str(message)


def _decode_payload(raw):
    if isinstance(raw, bytes):
        raw = raw.decode("utf-8")
    if not raw:
        return None
    return json.loads(raw)


def _read_http_error(error):
    try:
        return _decode_payload(error.read())
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError):
        return None


def _format_api_error(path, status, code, message, token=None):
    safe_path = _redact(path, token)
    safe_code = _redact(code, token)
    safe_message = _redact(message, token)
    return f"API request failed for {safe_path}: status={status} code={safe_code} message={safe_message}"


def api_request(
    base_url,
    path,
    method="GET",
    token=None,
    body=None,
    key=None,
    *,
    idempotency_key=None,
):
    """Call a JSON API and return its complete response envelope."""
    if idempotency_key is not None:
        if key is not None and key != idempotency_key:
            raise ValueError("both key and idempotency_key were supplied with different values")
        key = idempotency_key

    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + str(token)
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if key is not None:
        headers["Idempotency-Key"] = str(key)

    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=REQUEST_TIMEOUT) as response:
            status = getattr(response, "status", None)
            if status is None:
                getcode = getattr(response, "getcode", None)
                status = getcode() if callable(getcode) else 200
            status = int(status or 200)
            raw = response.read()
    except urllib.error.HTTPError as exception:
        try:
            payload = _read_http_error(exception)
            code, message = _error_fields(payload, exception.code)
            error_message = _format_api_error(path, exception.code, code, message, token)
        finally:
            exception.close()
        raise RuntimeError(error_message) from None
    except (OSError, TimeoutError, urllib.error.URLError) as exception:
        raise RuntimeError(_format_api_error(path, "network", "NETWORK_ERROR", exception, token)) from None
    except RuntimeError as exception:
        raise RuntimeError(_format_api_error(path, "network", "NETWORK_ERROR", exception, token)) from None

    if not 200 <= status < 300:
        try:
            payload = _decode_payload(raw)
        except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError):
            payload = None
        code, message = _error_fields(payload, status)
        raise RuntimeError(_format_api_error(path, status, code, message, token))

    try:
        payload = _decode_payload(raw)
    except (UnicodeDecodeError, json.JSONDecodeError, TypeError, ValueError) as exception:
        raise RuntimeError(_format_api_error(path, status, "INVALID_JSON", exception, token)) from None
    if not isinstance(payload, dict) or "data" not in payload:
        raise RuntimeError(
            _format_api_error(
                path,
                status,
                "INVALID_API_ENVELOPE",
                "response must contain a top-level data field",
                token,
            )
        )
    return payload


def _data(payload, path):
    if not isinstance(payload, dict) or "data" not in payload:
        raise RuntimeError(f"API response for {path} must contain a top-level data field")
    return payload["data"]


def select_wallet(wallets, source_amount):
    """Return the first wallet that can fund the requested source amount."""
    try:
        required = Decimal(str(source_amount))
    except (InvalidOperation, ValueError):
        raise RuntimeError(f"source amount prerequisite is invalid: {source_amount}") from None

    for wallet in wallets or []:
        if not isinstance(wallet, dict):
            continue
        try:
            available = Decimal(str(wallet.get("availableBalance", "0")))
        except (InvalidOperation, ValueError):
            continue
        if available >= required:
            return wallet
    raise RuntimeError(f"funded wallet prerequisite missing: no wallet has availableBalance >= {source_amount}")


def select_recipient(recipients):
    """Return the first active recipient without creating or modifying one."""
    for recipient in recipients or []:
        if isinstance(recipient, dict) and recipient.get("status") == "ACTIVE":
            return recipient
    raise RuntimeError("active recipient prerequisite missing: no active recipient is available")


def find_quote(quote_response, route_code):
    """Find the exact configured route in a quote response."""
    payload = quote_response
    if isinstance(payload, dict) and "quotes" not in payload and isinstance(payload.get("data"), dict):
        payload = payload["data"]
    quotes = payload.get("quotes", []) if isinstance(payload, dict) else []
    if not isinstance(quotes, list):
        quotes = []

    available_codes = []
    for quote in quotes:
        if isinstance(quote, dict) and quote.get("routeCode") is not None:
            available_codes.append(str(quote.get("routeCode")))
    for quote in quotes:
        if isinstance(quote, dict) and quote.get("routeCode") == route_code:
            return quote

    available = ", ".join(available_codes) if available_codes else "none"
    raise RuntimeError(f"requested route {route_code} prerequisite missing; available route codes: {available}")


def expected_attempts(scenario):
    """Return the number of payout attempts expected by the configured scenario."""
    if scenario not in SCENARIOS:
        raise ValueError(f"unsupported demo scenario: {scenario}")
    environment_key = FAILURE_ATTEMPTS_ENV[scenario]
    raw_value = os.environ.get(environment_key, "").strip()
    if not raw_value:
        failure_attempts = DEFAULT_FAILURE_ATTEMPTS[scenario]
    else:
        try:
            failure_attempts = int(raw_value)
        except ValueError:
            raise RuntimeError(f"{environment_key} must be a non-negative integer") from None
    if failure_attempts < 0:
        raise RuntimeError(f"{environment_key} must be a non-negative integer")
    return failure_attempts + 1 if scenario == "retry-success" else failure_attempts


def _payment_id(payment, path):
    if not isinstance(payment, dict) or not payment.get("id"):
        raise RuntimeError(f"payment prerequisite missing: {path} returned no payment id")
    return str(payment["id"])


def create_demo_payment(base_url, token, scenario, route_code):
    """Create and submit one real payment using the configured route."""
    if scenario not in SCENARIOS:
        raise ValueError(f"unsupported demo scenario: {scenario}")
    if not isinstance(route_code, str) or not route_code.strip():
        raise RuntimeError("requested route must be configured and non-empty")
    route_code = route_code.strip()
    attempt_count = expected_attempts(scenario)

    wallets = _data(
        api_request(base_url, "/api/wallets", method="GET", token=token),
        "/api/wallets",
    )
    wallet = select_wallet(wallets, SOURCE_AMOUNT)
    recipients = _data(
        api_request(base_url, "/api/recipients", method="GET", token=token),
        "/api/recipients",
    )
    recipient = select_recipient(recipients)
    if not wallet.get("walletId") or not recipient.get("id"):
        raise RuntimeError("payment prerequisite missing: wallet and recipient ids are required")
    if not wallet.get("currency") or not recipient.get("currency"):
        raise RuntimeError("payment prerequisite missing: wallet and recipient currencies are required")

    run_id = uuid.uuid4()
    keys = {
        "draft": f"demo-{scenario}-draft-{run_id}",
        "quotes": f"demo-{scenario}-quotes-{run_id}",
        "confirm": f"demo-{scenario}-confirm-{run_id}",
        "payout": f"demo-{scenario}-payout-{run_id}",
    }
    draft = _data(
        api_request(
            base_url,
            "/api/payments/draft",
            method="POST",
            token=token,
            body={
                "sourceWalletId": wallet["walletId"],
                "recipientId": recipient["id"],
                "sourceAmount": SOURCE_AMOUNT,
                "sourceCurrency": wallet["currency"],
                "payoutCurrency": recipient["currency"],
                "purpose": "FAMILY_SUPPORT",
                "preference": "BALANCED",
            },
            key=keys["draft"],
        ),
        "/api/payments/draft",
    )
    payment_id = _payment_id(draft, "/api/payments/draft")

    quote_response = _data(
        api_request(
            base_url,
            f"/api/payments/{payment_id}/quotes",
            method="POST",
            token=token,
            key=keys["quotes"],
        ),
        f"/api/payments/{payment_id}/quotes",
    )
    quote = find_quote(quote_response, route_code)
    quote_id = quote.get("id")
    if not quote_id:
        raise RuntimeError(f"requested route {route_code} prerequisite missing: quote has no id")

    _data(
        api_request(
            base_url,
            f"/api/payments/{payment_id}/confirm",
            method="POST",
            token=token,
            body={"quoteId": quote_id},
            key=keys["confirm"],
        ),
        f"/api/payments/{payment_id}/confirm",
    )
    _data(
        api_request(
            base_url,
            f"/api/payments/{payment_id}/submit-payout",
            method="POST",
            token=token,
            body={"routeCode": route_code},
            key=keys["payout"],
        ),
        f"/api/payments/{payment_id}/submit-payout",
    )

    return {
        "paymentId": payment_id,
        "routeCode": route_code,
        "scenario": scenario,
        "expectedAttempts": attempt_count,
    }


def _configured_route(scenario):
    environment_key = SCENARIOS[scenario]
    route_code = os.environ.get(environment_key, "").strip()
    if not route_code:
        raise RuntimeError(f"route prerequisite missing: {environment_key} must be set")
    return route_code


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scenario", choices=tuple(SCENARIOS))
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--base-url")
    args = parser.parse_args(argv)

    token = None
    password = None
    try:
        load_env(args.env_file)
        password = os.environ.get("SEED_CUSTOMER_PASSWORD", "").strip()
        if not password:
            raise RuntimeError("customer password prerequisite missing: SEED_CUSTOMER_PASSWORD is required")
        route_code = _configured_route(args.scenario)
        base_url = (args.base_url or os.environ.get("SEED_BASE_URL", "http://localhost:8080")).strip()
        if not base_url:
            raise RuntimeError("API base URL prerequisite missing: SEED_BASE_URL is required")
        email = os.environ.get("SEED_ALICE_EMAIL", "priya.sharma@gmail.com").strip()
        if not email:
            raise RuntimeError("customer email prerequisite missing: SEED_ALICE_EMAIL is required")

        login = _data(
            api_request(
                base_url,
                "/api/auth/login",
                method="POST",
                body={"email": email, "password": password},
            ),
            "/api/auth/login",
        )
        if not isinstance(login, dict) or not login.get("token"):
            raise RuntimeError("login prerequisite missing: API returned no customer token")
        token = str(login["token"])
        user = login.get("user")
        if not isinstance(user, dict) or user.get("kycStatus") != "VERIFIED":
            status = user.get("kycStatus") if isinstance(user, dict) else None
            raise RuntimeError(f"verified KYC prerequisite missing: customer session status is {status or 'UNKNOWN'}")

        result = create_demo_payment(base_url, token, args.scenario, route_code)
        print(
            f"scenario={result['scenario']} paymentId={result['paymentId']} "
            f"routeCode={result['routeCode']} expectedAttempts={result['expectedAttempts']}"
        )
        return 0
    except (KeyError, OSError, RuntimeError, TypeError, ValueError) as exception:
        print(f"demo failed: {_redact(exception, token, password)}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
