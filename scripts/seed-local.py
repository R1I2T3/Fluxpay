#!/usr/bin/env python3
"""Deterministically provision a local FluxPay schema and development identities."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from urllib.parse import urlsplit

from platform_commands import load_env


IDENTITIES = (
    ("system", "SEED_SYSTEM_EMAIL", "system@local.fluxpay", "SEED_SYSTEM_PASSWORD", "FluxPay System"),
    ("admin", "SEED_ADMIN_EMAIL", "admin@local.fluxpay", "SEED_ADMIN_PASSWORD", "Local Administrator"),
    ("customer", "SEED_ALICE_EMAIL", "alice@demo.io", "SEED_CUSTOMER_PASSWORD", "Alice Demo"),
    ("customer", "SEED_BOB_EMAIL", "bob@demo.io", "SEED_CUSTOMER_PASSWORD", "Bob Demo"),
)

SYSTEM_WALLET_ROLES = ("FX_CLEARING", "FX_GAIN_LOSS", "DEMO_CLEARING", "PAYOUT_CLEARING", "FEE_REVENUE")
CURRENCIES = ("USD", "EUR", "INR")
ROUTES = (
    ("STANDARD_BANK", "Standard bank", "Simulated standard bank", "STANDARD", "5.0000", "0.500000", 240, "99.50"),
    ("INSTANT_PAYOUT", "Instant payout", "Simulated instant payout", "INSTANT", "11.0000", "0.750000", 5, "98.00"),
    ("LOCAL_PARTNER", "Local partner", "Simulated local partner", "LOCAL_PARTNER", "2.0000", "0.250000", 60, "97.50"),
)


def request_json(base_url, path, body):
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=json.dumps(body).encode("utf-8"),
        headers={"Accept": "application/json", "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exception:
        try:
            payload = json.loads(exception.read().decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            payload = {"error": {"code": "HTTP_ERROR"}}
        return exception.code, payload


def register_or_login(base_url, email, password, full_name):
    status, payload = request_json(
        base_url,
        "/api/auth/register",
        {"email": email, "password": password, "fullName": full_name},
    )
    if status == 409:
        status, payload = request_json(base_url, "/api/auth/login", {"email": email, "password": password})
    if status not in (200, 201):
        code = payload.get("error", {}).get("code", "UNKNOWN") if isinstance(payload, dict) else "UNKNOWN"
        raise RuntimeError(f"identity provisioning failed for {email}: HTTP {status} ({code})")
    try:
        user = payload["data"]["user"]
        uuid.UUID(user["id"])
        if not payload["data"]["token"]:
            raise ValueError("empty token")
        return user
    except (KeyError, TypeError, ValueError) as exception:
        raise RuntimeError(f"identity provisioning returned an invalid response for {email}") from exception


def require_local_oracle(jdbc_url):
    prefix = "jdbc:oracle:thin:@"
    if not jdbc_url.startswith(prefix):
        raise ValueError("ORACLE_JDBC_URL must be an Oracle thin JDBC URL")
    address = jdbc_url[len(prefix) :]
    if not address.startswith("//"):
        raise ValueError("local Oracle URL must use //host:port/service syntax")
    parsed = urlsplit("oracle:" + address)
    if parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("local provisioning requires a local Oracle connection")
    if not parsed.path or parsed.path == "/":
        raise ValueError("local Oracle URL must name a service")
    return address[2:]


def _route_id(route_code):
    return uuid.uuid5(uuid.NAMESPACE_URL, "fluxpay:route:" + route_code).bytes


def provision_local_database(identities):
    jdbc_url = os.environ.get("ORACLE_JDBC_URL", "").strip()
    username = os.environ.get("ORACLE_USERNAME", "").strip()
    password = os.environ.get("ORACLE_PASSWORD", "")
    if not jdbc_url or not username or not password:
        raise ValueError("ORACLE_JDBC_URL, ORACLE_USERNAME and ORACLE_PASSWORD are required")
    dsn = require_local_oracle(jdbc_url)
    if username.upper() != "FLUXPAY":
        raise ValueError("local provisioning is restricted to the FLUXPAY application schema")

    try:
        import oracledb
    except ImportError as exception:
        raise RuntimeError("install the Python 'oracledb' package to provision local data") from exception

    with oracledb.connect(user=username, password=password, dsn=dsn) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM dual")
            actual_schema = cursor.fetchone()[0].upper()
            if actual_schema != "FLUXPAY":
                raise ValueError(f"connected schema is {actual_schema}, expected FLUXPAY")

            system_id = uuid.UUID(identities["system"]["id"]).bytes
            admin_id = uuid.UUID(identities["admin"]["id"]).bytes
            cursor.execute("UPDATE users SET role = 'SYSTEM' WHERE id = :user_id", user_id=system_id)
            cursor.execute("UPDATE users SET role = 'ADMIN' WHERE id = :user_id", user_id=admin_id)
            for user_id, role in ((system_id, "SYSTEM"), (admin_id, "ADMIN")):
                cursor.execute(
                    """MERGE INTO user_roles target
                       USING (SELECT :user_id AS user_id, :role AS role FROM dual) source
                       ON (target.user_id = source.user_id AND target.role = source.role)
                       WHEN NOT MATCHED THEN INSERT (user_id, role) VALUES (source.user_id, source.role)""",
                    user_id=user_id,
                    role=role,
                )

            for currency in CURRENCIES:
                for account_role in SYSTEM_WALLET_ROLES:
                    cursor.execute(
                        """MERGE INTO wallets target
                           USING (SELECT :user_id AS user_id, :currency AS currency,
                                         :account_role AS account_role FROM dual) source
                           ON (target.user_id = source.user_id AND target.currency = source.currency
                               AND target.account_role = source.account_role)
                           WHEN NOT MATCHED THEN
                             INSERT (id, user_id, currency, account_role, balance, held_balance, version, created_at)
                             VALUES (SYS_GUID(), source.user_id, source.currency, source.account_role,
                                     0, 0, 0, SYSTIMESTAMP)""",
                        user_id=system_id,
                        currency=currency,
                        account_role=account_role,
                    )

            for route in ROUTES:
                code, name, provider, route_type, fee, spread, minutes, success_rate = route
                cursor.execute(
                    """MERGE INTO payout_routes target
                       USING (SELECT :route_code AS route_code FROM dual) source
                       ON (target.route_code = source.route_code)
                       WHEN MATCHED THEN UPDATE SET route_name = :route_name,
                         provider_name = :provider_name, route_type = :route_type,
                         base_fee = :base_fee, fx_spread_percentage = :spread,
                         estimated_minutes = :minutes, success_rate = :success_rate,
                         active = 1, updated_at = SYSTIMESTAMP
                       WHEN NOT MATCHED THEN INSERT
                         (id, route_code, route_name, provider_name, route_type, base_fee,
                          fx_spread_percentage, estimated_minutes, success_rate, active,
                          version, created_at, updated_at)
                       VALUES (:route_id, source.route_code, :route_name, :provider_name,
                         :route_type, :base_fee, :spread, :minutes, :success_rate,
                         1, 0, SYSTIMESTAMP, SYSTIMESTAMP)""",
                    route_id=_route_id(code),
                    route_code=code,
                    route_name=name,
                    provider_name=provider,
                    route_type=route_type,
                    base_fee=fee,
                    spread=spread,
                    minutes=minutes,
                    success_rate=success_rate,
                )

            cursor.execute(
                "SELECT COUNT(*) FROM wallets WHERE user_id = :user_id AND account_role <> 'CUSTOMER'",
                user_id=system_id,
            )
            wallet_count = cursor.fetchone()[0]
            cursor.execute("SELECT COUNT(*) FROM payout_routes")
            route_count = cursor.fetchone()[0]
        connection.commit()

    return {
        "users": len(identities),
        "systemWallets": wallet_count,
        "routes": route_count,
        "systemUserId": str(uuid.UUID(identities["system"]["id"])),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--base-url")
    args = parser.parse_args()
    load_env(args.env_file)
    base_url = args.base_url or os.environ.get("SEED_BASE_URL", "http://localhost:8080")

    missing = [password_key for _, _, _, password_key, _ in IDENTITIES if not os.environ.get(password_key)]
    if missing:
        print("seed failed: missing " + ", ".join(sorted(set(missing))))
        return 2

    identities = {}
    try:
        for kind, email_key, default_email, password_key, full_name in IDENTITIES:
            email = os.environ.get(email_key, default_email).strip().lower()
            user = register_or_login(base_url, email, os.environ[password_key], full_name)
            identities[kind if kind != "customer" else email] = user
        result = provision_local_database(identities)
    except (OSError, RuntimeError, ValueError) as exception:
        print(f"seed failed: {exception}")
        return 2

    print(f"users={result['users']} system-wallets={result['systemWallets']} routes={result['routes']}")
    print(f"system-user-id={result['systemUserId']} (set FLUXPAY_SYSTEM_USER_ID before backend restart)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
