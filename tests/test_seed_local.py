import contextlib
import importlib.util
import io
import json
import os
import sys
import unittest
import uuid
from pathlib import Path
from unittest import mock

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))


def load_script():
    path = SCRIPTS_DIR / "seed-local.py"
    spec = importlib.util.spec_from_file_location("seed_local", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HttpResponse:
    def __init__(self, status, body):
        self.status = status
        self.body = json.dumps(body).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self):
        return self.body


class RouteCursor:
    def __init__(self, rows):
        self.rows = {row[0]: row for row in rows if row is not None}
        self.execute = mock.Mock()
        self.fetchone = mock.Mock(side_effect=self._fetchone)

    def _fetchone(self):
        call = self.execute.call_args
        statement = call.args[0]
        if "SELECT r.route_code" in statement:
            return self.rows.get(call.kwargs.get("route_code"))
        raise AssertionError(f"unexpected fetch after {statement}")

    @staticmethod
    def row(
        route_code,
        provider_id,
        destination_type="EXTERNAL_ACCOUNT",
        route_active=0,
        route_archived_at=None,
        provider_code="BANK_ALPHA",
        rail_type="BANK_NETWORK",
        provider_active=0,
        provider_archived_at=None,
    ):
        return (
            route_code,
            provider_id,
            destination_type,
            route_active,
            route_archived_at,
            provider_code,
            rail_type,
            provider_active,
            provider_archived_at,
        )


class SeedLocalTests(unittest.TestCase):
    def test_no_demo_configuration_keeps_seed_insert_only(self):
        script = load_script()
        cursor = RouteCursor([])

        self.assertEqual(script.load_demo_route_policies({}), ())
        script.activate_demo_routes(cursor, ())

        self.assertFalse(cursor.execute.called)

    def test_configured_bank_routes_activate_only_selected_inactive_records(self):
        script = load_script()
        environment = {
            "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
            "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD",
            "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "2",
            "FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE": "BANK_EXPRESS",
            "FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS": "6",
        }
        cursor = RouteCursor(
            [
                RouteCursor.row("BANK_STANDARD", b"bank-alpha-provider"),
                RouteCursor.row("BANK_EXPRESS", b"bank-alpha-provider"),
            ]
        )

        policies = script.load_demo_route_policies(environment)
        metadata = script.activate_demo_routes(cursor, policies)

        self.assertEqual(
            policies,
            (
                script.DemoRoutePolicy("retry-success", "BANK_STANDARD", 2, "BANK_ALPHA"),
                script.DemoRoutePolicy("refund", "BANK_EXPRESS", 6, "BANK_ALPHA"),
            ),
        )
        self.assertEqual(
            metadata,
            [
                {
                    "scenario": "retry-success",
                    "routeCode": "BANK_STANDARD",
                    "failureAttempts": 2,
                    "providerCode": "BANK_ALPHA",
                },
                {
                    "scenario": "refund",
                    "routeCode": "BANK_EXPRESS",
                    "failureAttempts": 6,
                    "providerCode": "BANK_ALPHA",
                },
            ],
        )
        statements = [call.args[0] for call in cursor.execute.call_args_list]
        provider_updates = [statement for statement in statements if "UPDATE transfer_providers" in statement]
        route_updates = [statement for statement in statements if "UPDATE transfer_routes" in statement]
        self.assertEqual(len(provider_updates), 1)
        self.assertIn("BANK_ALPHA", str(cursor.execute.call_args_list))
        self.assertEqual(len(route_updates), 2)
        for statement in provider_updates:
            normalized = " ".join(statement.split())
            self.assertIn("version = version + 1", normalized)
            self.assertIn("active = 0", normalized)
            self.assertIn("archived_at IS NULL", normalized)
        for statement in route_updates:
            normalized = " ".join(statement.split())
            self.assertIn("version = version + 1", normalized)
            self.assertIn("provider_id = :provider_id", normalized)
            self.assertIn("active = 0", normalized)
            self.assertIn("archived_at IS NULL", normalized)

    def test_demo_policy_validation_rejects_unsafe_configuration_before_activation(self):
        script = load_script()
        cases = {
            "unknown": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "DOES_NOT_EXIST",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "1",
            },
            "duplicate": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "1",
                "FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE": " BANK_STANDARD ",
                "FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS": "1",
            },
            "internal": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "FLUXPAY_INTERNAL",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "1",
            },
            "non-bank": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "REALTIME_INR",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "1",
            },
            "negative": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "-1",
            },
            "non-integer": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "not-an-integer",
            },
            "blank-integer": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "",
            },
            "simulation-disabled": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "false",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "BANK_STANDARD",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "0",
            },
            "positive-count-without-route": {
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
                "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": "1",
            },
        }

        for name, environment in cases.items():
            with self.subTest(name=name), self.assertRaises(ValueError):
                script.load_demo_route_policies(environment)

    def test_zero_count_route_is_validated_but_returns_no_policy(self):
        script = load_script()
        environment = {
            "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED": "true",
            "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE": "  BANK_STANDARD  ",
            "FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS": " 0 ",
        }
        self.assertEqual(script.load_demo_route_policies(environment), ())

    def test_activation_validates_every_persisted_row_before_any_update(self):
        script = load_script()
        policies = (
            script.DemoRoutePolicy("retry-success", "BANK_STANDARD", 2, "BANK_ALPHA"),
            script.DemoRoutePolicy("refund", "BANK_EXPRESS", 6, "BANK_ALPHA"),
        )
        invalid_rows = {
            "missing": None,
            "archived-route": RouteCursor.row("BANK_EXPRESS", b"bank-alpha-provider", route_archived_at="archived"),
            "archived-provider": RouteCursor.row(
                "BANK_EXPRESS", b"bank-alpha-provider", provider_archived_at="archived"
            ),
            "rebound": RouteCursor.row("BANK_EXPRESS", b"other-provider", provider_code="OTHER_PROVIDER"),
            "non-external": RouteCursor.row("BANK_EXPRESS", b"bank-alpha-provider", destination_type="INTERNAL_WALLET"),
            "non-bank": RouteCursor.row("BANK_EXPRESS", b"bank-alpha-provider", rail_type="PARTNER_NETWORK"),
        }

        for name, second_row in invalid_rows.items():
            with self.subTest(name=name):
                cursor = RouteCursor([RouteCursor.row("BANK_STANDARD", b"bank-alpha-provider"), second_row])
                with self.assertRaises(RuntimeError):
                    script.activate_demo_routes(cursor, policies)
                statements = [call.args[0] for call in cursor.execute.call_args_list]
                self.assertFalse(any("UPDATE transfer_providers" in statement for statement in statements))
                self.assertFalse(any("UPDATE transfer_routes" in statement for statement in statements))

    def test_activation_does_not_touch_already_active_records(self):
        script = load_script()
        cursor = RouteCursor(
            [
                RouteCursor.row("BANK_STANDARD", b"bank-alpha-provider", route_active=1, provider_active=1),
            ]
        )
        policies = (script.DemoRoutePolicy("retry-success", "BANK_STANDARD", 2, "BANK_ALPHA"),)
        script.activate_demo_routes(cursor, policies)
        self.assertEqual(cursor.execute.call_count, 1)
        self.assertIn("SELECT r.route_code", cursor.execute.call_args.args[0])

    def test_provisions_every_system_currency_role_with_insert_only_merges_on_rerun(self):
        script = load_script()
        system_id = "11111111-1111-1111-1111-111111111111"
        identities = {
            "system": {"id": system_id},
            "admin": {"id": "22222222-2222-2222-2222-222222222222"},
        }
        database = mock.MagicMock()
        connection = database.connect.return_value.__enter__.return_value
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.side_effect = [("FLUXPAY",), (15,), (4,), (13,)] * 2
        expected = {
            (uuid.UUID(system_id).bytes, currency, role)
            for currency in ("USD", "EUR", "INR")
            for role in (
                "FX_CLEARING",
                "FX_GAIN_LOSS",
                "DEMO_CLEARING",
                "PAYOUT_CLEARING",
                "FEE_REVENUE",
            )
        }
        with (
            mock.patch.dict(sys.modules, {"oracledb": database}),
            mock.patch.dict(
                os.environ,
                {
                    "ORACLE_JDBC_URL": "jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
                    "ORACLE_USERNAME": "FLUXPAY",
                    "ORACLE_PASSWORD": "test-only",
                },
                clear=True,
            ),
        ):
            for _ in range(2):
                cursor.execute.reset_mock()
                script.provision_local_database(identities)
                merges = [call for call in cursor.execute.call_args_list if "MERGE INTO wallets" in call.args[0]]
                self.assertEqual(
                    {(call.kwargs["user_id"], call.kwargs["currency"], call.kwargs["account_role"]) for call in merges},
                    expected,
                )
                self.assertEqual(len(merges), 15)
                for call in merges:
                    sql = " ".join(call.args[0].split())
                    self.assertIn("target.user_id = source.user_id", sql)
                    self.assertIn("target.currency = source.currency", sql)
                    self.assertIn("target.account_role = source.account_role", sql)
                    self.assertIn("WHEN NOT MATCHED THEN INSERT", sql)
                    self.assertNotIn("WHEN MATCHED THEN", sql)

                provider_merges = [
                    call for call in cursor.execute.call_args_list if "MERGE INTO transfer_providers" in call.args[0]
                ]
                self.assertEqual(
                    {call.kwargs["provider_code"] for call in provider_merges},
                    {"FLUXPAY", "BANK_ALPHA", "REAL_TIME", "PARTNER"},
                )
                self.assertEqual(len(provider_merges), 4)
                for call in provider_merges:
                    sql = " ".join(call.args[0].split())
                    self.assertIn("target.provider_code = source.provider_code", sql)
                    self.assertIn("WHEN NOT MATCHED THEN INSERT", sql)
                    self.assertNotIn("WHEN MATCHED THEN", sql)
                self.assertEqual(
                    {call.kwargs["provider_code"]: call.kwargs["system_protected"] for call in provider_merges}[
                        "FLUXPAY"
                    ],
                    1,
                )

                route_merges = [
                    call for call in cursor.execute.call_args_list if "MERGE INTO transfer_routes" in call.args[0]
                ]
                self.assertEqual({call.kwargs["payout_currency"] for call in route_merges}, {"INR", "USD", "EUR"})
                self.assertEqual(
                    {call.kwargs["route_code"] for call in route_merges},
                    {
                        "FLUXPAY_INTERNAL",
                        "FLUXPAY_INTERNAL_USD",
                        "FLUXPAY_INTERNAL_EUR",
                        "BANK_STANDARD",
                        "BANK_EXPRESS",
                        "REALTIME_INR",
                        "PARTNER_INR",
                        "BANK_USD_STANDARD",
                        "REALTIME_USD",
                        "PARTNER_USD",
                        "BANK_EUR_STANDARD",
                        "REALTIME_EUR",
                        "PARTNER_EUR",
                    },
                )
                self.assertEqual(len(route_merges), 13)
                for call in route_merges:
                    sql = " ".join(call.args[0].split())
                    self.assertIn("target.route_code = source.route_code", sql)
                    self.assertIn("WHEN NOT MATCHED THEN INSERT", sql)
                    self.assertNotIn("WHEN MATCHED THEN", sql)
                    self.assertNotIn("payout_routes", sql)
                self.assertFalse(
                    any(
                        "UPDATE transfer_providers" in call.args[0] or "UPDATE transfer_routes" in call.args[0]
                        for call in cursor.execute.call_args_list
                    )
                )
        self.assertEqual(connection.commit.call_count, 2)

    def test_registers_full_contract_without_client_controlled_role_and_reports_actual_counts(self):
        script = load_script()
        requests = []

        def urlopen(request, timeout):
            requests.append(request)
            body = json.loads(request.data)
            email = body["email"]
            if request.full_url.endswith("/register"):
                user_id = {
                    "fluxpay.system@gmail.com": "11111111-1111-1111-1111-111111111111",
                    "fluxpay.admin@gmail.com": "22222222-2222-2222-2222-222222222222",
                    "priya.sharma@gmail.com": "33333333-3333-3333-3333-333333333333",
                    "arjun.mehta@gmail.com": "44444444-4444-4444-4444-444444444444",
                }[email]
                return HttpResponse(201, {"data": {"token": "secret", "user": {"id": user_id}}})
            return HttpResponse(200, {"data": {"token": "secret", "user": {"id": "unused"}}})

        provisioned = {
            "users": 4,
            "systemWallets": 15,
            "providers": 4,
            "routes": 13,
            "systemUserId": "11111111-1111-1111-1111-111111111111",
            "demoRoutes": [],
        }
        output = io.StringIO()
        with (
            mock.patch.object(sys, "argv", ["seed-local.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.dict(
                os.environ,
                {
                    "SEED_SYSTEM_PASSWORD": "SystemPass123!",
                    "SEED_ADMIN_PASSWORD": "AdminPass123!",
                    "SEED_CUSTOMER_PASSWORD": "CustomerPass123!",
                    "ORACLE_JDBC_URL": "jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
                    "ORACLE_USERNAME": "FLUXPAY",
                    "ORACLE_PASSWORD": "database-secret",
                },
                clear=True,
            ),
            mock.patch.object(script.urllib.request, "urlopen", side_effect=urlopen),
            mock.patch.object(script, "provision_local_database", return_value=provisioned),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(script.main(), 0)

        register_bodies = [json.loads(request.data) for request in requests if request.full_url.endswith("/register")]
        self.assertEqual(len(register_bodies), 4)
        self.assertTrue(all(body["fullName"].strip() for body in register_bodies))
        self.assertTrue(all("role" not in body for body in register_bodies))
        text = output.getvalue()
        self.assertIn("users=4 system-wallets=15 providers=4 routes=13", text)
        self.assertNotIn("policies=", text)
        self.assertIn("demo-routes=none active-flags-unchanged", text)
        self.assertNotIn("secret", text)
        self.assertNotIn("Pass123", text)

    def test_main_prints_each_configured_demo_route_without_secrets(self):
        script = load_script()
        result = {
            "users": 4,
            "systemWallets": 15,
            "providers": 4,
            "routes": 13,
            "systemUserId": "11111111-1111-1111-1111-111111111111",
            "demoRoutes": [
                {
                    "scenario": "retry-success",
                    "routeCode": "BANK_STANDARD",
                    "failureAttempts": 2,
                    "providerCode": "BANK_ALPHA",
                }
            ],
        }
        output = io.StringIO()
        with (
            mock.patch.object(sys, "argv", ["seed-local.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.dict(
                os.environ,
                {
                    "SEED_SYSTEM_PASSWORD": "SystemPass123!",
                    "SEED_ADMIN_PASSWORD": "AdminPass123!",
                    "SEED_CUSTOMER_PASSWORD": "CustomerPass123!",
                },
                clear=True,
            ),
            mock.patch.object(
                script,
                "register_or_login",
                return_value={"id": "11111111-1111-1111-1111-111111111111"},
            ),
            mock.patch.object(script, "provision_local_database", return_value=result),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(script.main(), 0)

        text = output.getvalue()
        self.assertIn("demo-route=retry-success", text)
        self.assertIn("route=BANK_STANDARD", text)
        self.assertIn("failure-attempts=2", text)
        self.assertIn("provider=BANK_ALPHA", text)
        self.assertNotIn("SystemPass123", text)

    def test_duplicate_registration_logs_in_and_remains_idempotent(self):
        script = load_script()
        calls = []

        def request_json(base_url, path, body):
            calls.append((path, body["email"]))
            if path.endswith("/register"):
                return 409, {"error": {"code": "EMAIL_EXISTS"}}
            return 200, {
                "data": {
                    "token": "not-printed",
                    "user": {"id": "11111111-1111-1111-1111-111111111111"},
                }
            }

        with mock.patch.object(script, "request_json", side_effect=request_json):
            user = script.register_or_login("http://localhost:8080", "same@example.com", "ValidPass123!", "Same User")

        self.assertEqual(user["id"], "11111111-1111-1111-1111-111111111111")
        self.assertEqual(calls, [("/api/auth/register", "same@example.com"), ("/api/auth/login", "same@example.com")])

    def test_rejects_nonlocal_database_before_provisioning(self):
        script = load_script()
        with self.assertRaisesRegex(ValueError, "local Oracle"):
            script.require_local_oracle("jdbc:oracle:thin:@//db.example.com:1521/PROD")


if __name__ == "__main__":
    unittest.main()
