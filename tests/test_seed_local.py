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


class SeedLocalTests(unittest.TestCase):
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
        cursor.fetchone.side_effect = [("FLUXPAY",), (15,), (3,)] * 2
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
                merges = [
                    call for call in cursor.execute.call_args_list
                    if "MERGE INTO wallets" in call.args[0]
                ]
                self.assertEqual(
                    {
                        (call.kwargs["user_id"], call.kwargs["currency"], call.kwargs["account_role"])
                        for call in merges
                    },
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
                    "system@local.fluxpay": "11111111-1111-1111-1111-111111111111",
                    "admin@local.fluxpay": "22222222-2222-2222-2222-222222222222",
                    "alice@demo.io": "33333333-3333-3333-3333-333333333333",
                    "bob@demo.io": "44444444-4444-4444-4444-444444444444",
                }[email]
                return HttpResponse(201, {"data": {"token": "secret", "user": {"id": user_id}}})
            return HttpResponse(200, {"data": {"token": "secret", "user": {"id": "unused"}}})

        provisioned = {
            "users": 4,
            "systemWallets": 15,
            "routes": 3,
            "systemUserId": "11111111-1111-1111-1111-111111111111",
        }
        output = io.StringIO()
        with (
            mock.patch.object(sys, "argv", ["seed-local.py"]),
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

        register_bodies = [
            json.loads(request.data)
            for request in requests
            if request.full_url.endswith("/register")
        ]
        self.assertEqual(len(register_bodies), 4)
        self.assertTrue(all(body["fullName"].strip() for body in register_bodies))
        self.assertTrue(all("role" not in body for body in register_bodies))
        text = output.getvalue()
        self.assertIn("users=4 system-wallets=15 routes=3", text)
        self.assertNotIn("policies=", text)
        self.assertNotIn("secret", text)
        self.assertNotIn("Pass123", text)

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
            user = script.register_or_login(
                "http://localhost:8080", "same@example.com", "ValidPass123!", "Same User"
            )

        self.assertEqual(user["id"], "11111111-1111-1111-1111-111111111111")
        self.assertEqual(calls, [("/api/auth/register", "same@example.com"), ("/api/auth/login", "same@example.com")])

    def test_rejects_nonlocal_database_before_provisioning(self):
        script = load_script()
        with self.assertRaisesRegex(ValueError, "local Oracle"):
            script.require_local_oracle("jdbc:oracle:thin:@//db.example.com:1521/PROD")


if __name__ == "__main__":
    unittest.main()
