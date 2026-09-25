#!/usr/bin/env python3
"""Deterministically provision the FluxPay schema, development identities, system
wallets, and the transfer provider/route catalogue. Catalogue seeding is insert-only, so
reruns and administrator edits never conflict."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass
from urllib.parse import urlsplit

from platform_commands import load_env

IDENTITIES = (
    ("system", "SEED_SYSTEM_EMAIL", "fluxpay.system@gmail.com", "SEED_SYSTEM_PASSWORD", "FluxPay System"),
    ("admin", "SEED_ADMIN_EMAIL", "fluxpay.admin@gmail.com", "SEED_ADMIN_PASSWORD", "Platform Administrator"),
    ("customer", "SEED_ALICE_EMAIL", "priya.sharma@gmail.com", "SEED_CUSTOMER_PASSWORD", "Priya Sharma"),
    ("customer", "SEED_BOB_EMAIL", "arjun.mehta@gmail.com", "SEED_CUSTOMER_PASSWORD", "Arjun Mehta"),
)

SYSTEM_WALLET_ROLES = ("FX_CLEARING", "FX_GAIN_LOSS", "DEMO_CLEARING", "PAYOUT_CLEARING", "FEE_REVENUE")
CURRENCIES = ("USD", "EUR", "INR")
# Sample catalogue: code, name, code-shipped rail type, active.
# The external providers stay inactive because their simulated rails are only installed
# when FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true; an administrator activates them
# after enabling simulated payouts. The internal provider is always usable.
PROVIDERS = (
    ("FLUXPAY", "FluxPay", "INTERNAL_LEDGER", 1),
    ("BANK_ALPHA", "HDFC Bank", "BANK_NETWORK", 0),
    ("REAL_TIME", "UPI Instant Network", "REAL_TIME_NETWORK", 0),
    ("PARTNER", "SBI Partner Network", "PARTNER_NETWORK", 0),
)
SYSTEM_PROTECTED_PROVIDERS = frozenset({"FLUXPAY"})
# Routes: code, name, provider code, destination type, country, currency, base fee,
# fx spread percentage, eta minutes, configured success rate, active, system-protected.
ROUTES = (
    (
        "FLUXPAY_INTERNAL",
        "FluxPay wallet",
        "FLUXPAY",
        "INTERNAL_WALLET",
        None,
        "INR",
        "0.0000",
        "0.000000",
        1,
        "100.00",
        1,
        1,
    ),
    (
        "FLUXPAY_INTERNAL_USD",
        "FluxPay wallet USD",
        "FLUXPAY",
        "INTERNAL_WALLET",
        None,
        "USD",
        "0.0000",
        "0.000000",
        1,
        "100.00",
        1,
        1,
    ),
    (
        "FLUXPAY_INTERNAL_EUR",
        "FluxPay wallet EUR",
        "FLUXPAY",
        "INTERNAL_WALLET",
        None,
        "EUR",
        "0.0000",
        "0.000000",
        1,
        "100.00",
        1,
        1,
    ),
    (
        "BANK_STANDARD",
        "HDFC INR Standard",
        "BANK_ALPHA",
        "EXTERNAL_ACCOUNT",
        "IN",
        "INR",
        "5.0000",
        "0.500000",
        240,
        "99.00",
        0,
        0,
    ),
    (
        "BANK_EXPRESS",
        "HDFC INR Express",
        "BANK_ALPHA",
        "EXTERNAL_ACCOUNT",
        "IN",
        "INR",
        "11.0000",
        "0.750000",
        30,
        "98.00",
        0,
        0,
    ),
    (
        "REALTIME_INR",
        "UPI Instant INR",
        "REAL_TIME",
        "EXTERNAL_ACCOUNT",
        "IN",
        "INR",
        "8.0000",
        "0.400000",
        5,
        "97.50",
        0,
        0,
    ),
    (
        "PARTNER_INR",
        "SBI Partner INR",
        "PARTNER",
        "EXTERNAL_ACCOUNT",
        "IN",
        "INR",
        "2.0000",
        "0.250000",
        60,
        "97.50",
        0,
        0,
    ),
    (
        "BANK_USD_STANDARD",
        "HDFC US Standard",
        "BANK_ALPHA",
        "EXTERNAL_ACCOUNT",
        "US",
        "USD",
        "3.0000",
        "0.500000",
        240,
        "99.00",
        0,
        0,
    ),
    (
        "REALTIME_USD",
        "UPI Instant USD",
        "REAL_TIME",
        "EXTERNAL_ACCOUNT",
        "US",
        "USD",
        "4.0000",
        "0.400000",
        5,
        "97.50",
        0,
        0,
    ),
    (
        "PARTNER_USD",
        "SBI Partner USD",
        "PARTNER",
        "EXTERNAL_ACCOUNT",
        "US",
        "USD",
        "1.5000",
        "0.250000",
        60,
        "97.50",
        0,
        0,
    ),
    (
        "BANK_EUR_STANDARD",
        "HDFC EU Standard",
        "BANK_ALPHA",
        "EXTERNAL_ACCOUNT",
        "DE",
        "EUR",
        "2.5000",
        "0.450000",
        240,
        "99.00",
        0,
        0,
    ),
    (
        "REALTIME_EUR",
        "UPI Instant EUR",
        "REAL_TIME",
        "EXTERNAL_ACCOUNT",
        "DE",
        "EUR",
        "3.5000",
        "0.350000",
        5,
        "97.50",
        0,
        0,
    ),
    (
        "PARTNER_EUR",
        "SBI Partner EUR",
        "PARTNER",
        "EXTERNAL_ACCOUNT",
        "DE",
        "EUR",
        "1.2500",
        "0.200000",
        60,
        "97.50",
        0,
        0,
    ),
)


@dataclass(frozen=True)
class DemoRoutePolicy:
    scenario: str
    route_code: str
    failure_attempts: int
    provider_code: str


DEMO_ROUTE_POLICY_ENV = (
    (
        "retry-success",
        "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE",
        "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS",
    ),
    (
        "refund",
        "FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE",
        "FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS",
    ),
)

DEMO_ROUTE_LOOKUP_SQL = """SELECT r.route_code,
       r.provider_id,
       r.destination_type,
       r.active AS route_active,
       r.archived_at AS route_archived_at,
       p.provider_code,
       p.rail_type,
       p.active AS provider_active,
       p.archived_at AS provider_archived_at
  FROM transfer_routes r
  JOIN transfer_providers p ON p.id = r.provider_id
 WHERE r.route_code = :route_code"""

DEMO_PROVIDER_ACTIVATION_SQL = """UPDATE transfer_providers
   SET active = 1, version = version + 1, updated_at = SYSTIMESTAMP
 WHERE provider_code = :provider_code AND active = 0 AND archived_at IS NULL"""

DEMO_ROUTE_ACTIVATION_SQL = """UPDATE transfer_routes
   SET active = 1, version = version + 1, updated_at = SYSTIMESTAMP
 WHERE route_code = :route_code AND provider_id = :provider_id
   AND active = 0 AND archived_at IS NULL"""


def _trimmed_environment_value(environ, key):
    value = environ.get(key)
    if value is None:
        return ""
    return str(value).strip()


def load_demo_route_policies(environ):
    """Parse and validate the opt-in local simulated-payout route policies."""
    route_catalog = {route[0]: route for route in ROUTES}
    provider_catalog = {provider[0]: provider for provider in PROVIDERS}
    simulation_enabled = (
        _trimmed_environment_value(environ, "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED").lower() == "true"
    )
    policies = []
    configured_routes = set()

    for scenario, route_key, attempts_key in DEMO_ROUTE_POLICY_ENV:
        route_code = _trimmed_environment_value(environ, route_key)
        raw_attempts = environ.get(attempts_key)
        if raw_attempts is None:
            failure_attempts = 0
        else:
            try:
                failure_attempts = int(str(raw_attempts).strip())
            except ValueError as exception:
                raise ValueError(f"{attempts_key} must be an integer") from exception
        if failure_attempts < 0:
            raise ValueError(f"{attempts_key} must not be negative")
        if failure_attempts > 0 and not route_code:
            raise ValueError(f"{attempts_key} requires {route_key}")
        if not route_code:
            continue
        if not simulation_enabled:
            raise ValueError(f"{route_key} requires FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true")
        if route_code in configured_routes:
            raise ValueError(f"demo failure route codes must be distinct: {route_code}")
        configured_routes.add(route_code)

        route = route_catalog.get(route_code)
        if route is None:
            raise ValueError(f"unknown demo route code: {route_code}")
        provider_code = route[2]
        provider = provider_catalog.get(provider_code)
        if provider is None or route[3] != "EXTERNAL_ACCOUNT" or provider[2] != "BANK_NETWORK":
            raise ValueError(f"demo route {route_code} must be an external-account route on a BANK_NETWORK provider")
        if failure_attempts > 0:
            policies.append(
                DemoRoutePolicy(
                    scenario=scenario,
                    route_code=route_code,
                    failure_attempts=failure_attempts,
                    provider_code=provider_code,
                )
            )

    return tuple(policies)


def _activation_row(cursor, policy):
    cursor.execute(DEMO_ROUTE_LOOKUP_SQL, route_code=policy.route_code)
    row = cursor.fetchone()
    if row is None:
        raise RuntimeError(f"demo route {policy.route_code} is missing from the seeded catalogue")
    try:
        (
            route_code,
            provider_id,
            destination_type,
            route_active,
            route_archived_at,
            provider_code,
            rail_type,
            provider_active,
            provider_archived_at,
        ) = row
    except (TypeError, ValueError) as exception:
        raise RuntimeError(f"demo route {policy.route_code} returned an invalid catalogue row") from exception
    if route_code != policy.route_code:
        raise RuntimeError(f"demo route {policy.route_code} was rebound in the catalogue")
    if route_archived_at is not None:
        raise RuntimeError(f"demo route {policy.route_code} is archived")
    if provider_archived_at is not None:
        raise RuntimeError(f"demo provider {policy.provider_code} for {policy.route_code} is archived")
    if provider_code != policy.provider_code:
        raise RuntimeError(
            f"demo route {policy.route_code} is bound to {provider_code}, expected {policy.provider_code}"
        )
    if destination_type != "EXTERNAL_ACCOUNT":
        raise RuntimeError(f"demo route {policy.route_code} is not an external-account route")
    if rail_type != "BANK_NETWORK":
        raise RuntimeError(f"demo route {policy.route_code} is not on a BANK_NETWORK provider")
    if provider_id is None:
        raise RuntimeError(f"demo route {policy.route_code} has no provider binding")
    return provider_id, route_active, provider_active


def _is_inactive(value):
    return value in (0, False) or (isinstance(value, str) and value.strip().lower() in ("0", "false"))


def activate_demo_routes(cursor, policies):
    """Validate every selected persisted row, then activate only inactive records."""
    validated = []
    providers = {}
    for policy in policies:
        provider_id, route_active, provider_active = _activation_row(cursor, policy)
        validated.append((policy, provider_id, route_active))
        providers.setdefault(policy.provider_code, provider_active)

    for provider_code, provider_active in providers.items():
        if _is_inactive(provider_active):
            cursor.execute(DEMO_PROVIDER_ACTIVATION_SQL, provider_code=provider_code)

    for policy, provider_id, route_active in validated:
        if _is_inactive(route_active):
            cursor.execute(
                DEMO_ROUTE_ACTIVATION_SQL,
                route_code=policy.route_code,
                provider_id=provider_id,
            )

    return [
        {
            "scenario": policy.scenario,
            "routeCode": policy.route_code,
            "failureAttempts": policy.failure_attempts,
            "providerCode": policy.provider_code,
        }
        for policy, _, _ in validated
    ]


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


def _provider_id(provider_code):
    return uuid.uuid5(uuid.NAMESPACE_URL, "fluxpay:provider:" + provider_code).bytes


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

    policies = load_demo_route_policies(os.environ)

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

            for code, name, rail_type, active in PROVIDERS:
                cursor.execute(
                    """MERGE INTO transfer_providers target
                       USING (SELECT :provider_code AS provider_code FROM dual) source
                       ON (target.provider_code = source.provider_code)
                       WHEN NOT MATCHED THEN INSERT
                         (id, provider_code, provider_name, rail_type, active, system_protected,
                          version, created_at, updated_at)
                       VALUES (:provider_id, source.provider_code, :provider_name, :rail_type,
                         :active, :system_protected, 0, SYSTIMESTAMP, SYSTIMESTAMP)""",
                    provider_id=_provider_id(code),
                    provider_code=code,
                    provider_name=name,
                    rail_type=rail_type,
                    active=active,
                    system_protected=1 if code in SYSTEM_PROTECTED_PROVIDERS else 0,
                )

            for route in ROUTES:
                (
                    code,
                    name,
                    provider_code,
                    destination_type,
                    country,
                    currency,
                    fee,
                    spread,
                    minutes,
                    success_rate,
                    active,
                    system_protected,
                ) = route
                cursor.execute(
                    """MERGE INTO transfer_routes target
                       USING (SELECT :route_code AS route_code FROM dual) source
                       ON (target.route_code = source.route_code)
                       WHEN NOT MATCHED THEN INSERT
                         (id, provider_id, route_code, route_name, destination_type,
                          destination_country, payout_currency, base_fee, fx_spread_percentage,
                          estimated_minutes, configured_success_rate, active, system_protected,
                          version, created_at, updated_at)
                       VALUES (:route_id, :provider_id, source.route_code, :route_name,
                         :destination_type, :destination_country, :payout_currency, :base_fee,
                         :spread, :minutes, :success_rate, :active, :system_protected,
                         0, SYSTIMESTAMP, SYSTIMESTAMP)""",
                    route_id=_route_id(code),
                    provider_id=_provider_id(provider_code),
                    route_code=code,
                    route_name=name,
                    destination_type=destination_type,
                    destination_country=country,
                    payout_currency=currency,
                    base_fee=fee,
                    spread=spread,
                    minutes=minutes,
                    success_rate=success_rate,
                    active=active,
                    system_protected=system_protected,
                )

            demo_routes = activate_demo_routes(cursor, policies)

            cursor.execute(
                "SELECT COUNT(*) FROM wallets WHERE user_id = :user_id AND account_role <> 'CUSTOMER'",
                user_id=system_id,
            )
            wallet_count = cursor.fetchone()[0]
            cursor.execute("SELECT COUNT(*) FROM transfer_providers")
            provider_count = cursor.fetchone()[0]
            cursor.execute("SELECT COUNT(*) FROM transfer_routes")
            route_count = cursor.fetchone()[0]
        connection.commit()

    return {
        "users": len(identities),
        "systemWallets": wallet_count,
        "providers": provider_count,
        "routes": route_count,
        "systemUserId": str(uuid.UUID(identities["system"]["id"])),
        "demoRoutes": demo_routes,
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

    print(
        f"users={result['users']} system-wallets={result['systemWallets']} "
        f"providers={result['providers']} routes={result['routes']}"
    )
    print(f"system-user-id={result['systemUserId']} (set FLUXPAY_SYSTEM_USER_ID before backend restart)")
    demo_routes = result.get("demoRoutes", [])
    if demo_routes:
        for demo_route in demo_routes:
            print(
                f"demo-route={demo_route['scenario']} route={demo_route['routeCode']} "
                f"failure-attempts={demo_route['failureAttempts']} provider={demo_route['providerCode']}"
            )
    else:
        print("demo-routes=none active-flags-unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
