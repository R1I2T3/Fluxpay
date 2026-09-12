import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock


PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))


def load_script(name):
    path = SCRIPTS_DIR / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HttpResponse:
    status = 200

    def __init__(self, body):
        self.body = json.dumps(body).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self):
        return self.body


class FakeCursor:
    def __init__(self, imbalances, ungrouped):
        self.imbalances = imbalances
        self.ungrouped = ungrouped
        self.statements = []
        self.current = ""

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def execute(self, statement):
        self.current = statement
        self.statements.append(statement)

    def fetchall(self):
        if "HAVING" in self.current:
            return self.imbalances
        if "journal_reference IS NULL" in self.current:
            return self.ungrouped
        return []


class FakeConnection:
    def __init__(self, imbalances, ungrouped):
        self.fake_cursor = FakeCursor(imbalances, ungrouped)

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def cursor(self):
        return self.fake_cursor


class M2SeedScriptTests(unittest.TestCase):
    def test_seed_uses_three_deterministic_api_operations_and_never_prints_token(self):
        script = load_script("seed_m2")
        user_id = "11111111-1111-1111-1111-111111111111"
        token = "secret-signed-token"
        requests = []

        def urlopen(request, timeout):
            requests.append((request, timeout))
            if request.get_method() == "GET":
                return HttpResponse(
                    {
                        "data": [
                            {
                                "walletId": "22222222-2222-2222-2222-222222222222",
                                "currency": "USD",
                                "heldBalance": "0.0000",
                                "availableBalance": "500.0000",
                            }
                        ]
                    }
                )
            return HttpResponse({"data": {"currency": "USD"}})

        output = io.StringIO()
        with (
            mock.patch.object(
                sys,
                "argv",
                ["seed_m2.py", "--user-id", user_id, "--base-url", "http://backend:8080"],
            ),
            mock.patch.dict(script.os.environ, {"M2_BEARER_TOKEN": token}, clear=True),
            mock.patch.object(script.urllib.request, "urlopen", side_effect=urlopen),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(script.main(), 0)

        self.assertEqual([request.get_method() for request, _ in requests], ["POST", "POST", "POST", "GET"])
        expected = {"USD": "500.0000", "INR": "10000.0000", "EUR": "50.0000"}
        for request, timeout in requests[:3]:
            body = json.loads(request.data)
            currency = body["currency"]
            self.assertEqual(body["amount"], expected[currency])
            self.assertEqual(request.get_header("Idempotency-key"), f"seed-m2:{user_id}:{currency}:v1")
            self.assertEqual(request.get_header("Authorization"), f"Bearer {token}")
            self.assertEqual(timeout, 5)
        self.assertTrue(requests[-1][0].full_url.endswith("/api/wallets"))
        self.assertNotIn(token, output.getvalue())
        self.assertIn("USD available=500.0000 held=0.0000", output.getvalue())

    def test_seed_rejects_missing_token_before_contacting_backend(self):
        script = load_script("seed_m2")
        output = io.StringIO()
        with (
            mock.patch.object(
                sys,
                "argv",
                ["seed_m2.py", "--user-id", "11111111-1111-1111-1111-111111111111"],
            ),
            mock.patch.dict(script.os.environ, {}, clear=True),
            mock.patch.object(script.urllib.request, "urlopen") as urlopen,
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(script.main(), 2)

        urlopen.assert_not_called()
        self.assertIn("M2_BEARER_TOKEN is required", output.getvalue())

    def test_seed_reports_backend_failure_without_exposing_token(self):
        script = load_script("seed_m2")
        token = "do-not-print-this"
        output = io.StringIO()
        with (
            mock.patch.object(
                sys,
                "argv",
                ["seed_m2.py", "--user-id", "11111111-1111-1111-1111-111111111111"],
            ),
            mock.patch.dict(script.os.environ, {"M2_BEARER_TOKEN": token}, clear=True),
            mock.patch.object(script.urllib.request, "urlopen", side_effect=OSError("offline")),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(script.main(), 2)

        self.assertIn("seed failed", output.getvalue())
        self.assertNotIn(token, output.getvalue())


class M2LedgerCheckScriptTests(unittest.TestCase):
    def test_converts_project_jdbc_url_to_python_oracle_dsn(self):
        script = load_script("check_m2_ledger")
        self.assertEqual(
            script.oracle_dsn("jdbc:oracle:thin:@//localhost:1521/FREEPDB1"),
            "localhost:1521/FREEPDB1",
        )

    def test_read_only_check_reports_imbalances_and_ungrouped_entries_without_credentials(self):
        script = load_script("check_m2_ledger")
        connection = FakeConnection(
            [("M2-FX-one", "USD", "100.0000", "99.5000")],
            [("EUR", "CREDIT", 2, "7.0000")],
        )
        calls = []

        def connect(**kwargs):
            calls.append(kwargs)
            return connection

        fake_oracledb = types.SimpleNamespace(connect=connect)
        output = io.StringIO()
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text(
                "ORACLE_JDBC_URL=jdbc:oracle:thin:@//dbhost:1521/FREEPDB1\n"
                "ORACLE_USERNAME=fluxpay\n"
                "ORACLE_PASSWORD=secret-db-password\n",
                encoding="utf-8",
            )
            with (
                mock.patch.object(sys, "argv", ["check_m2_ledger.py", "--env-file", str(env_file)]),
                mock.patch.dict(sys.modules, {"oracledb": fake_oracledb}),
                mock.patch.dict(script.os.environ, {}, clear=True),
                contextlib.redirect_stdout(output),
            ):
                self.assertEqual(script.main(), 1)

        self.assertEqual(
            calls,
            [{"user": "fluxpay", "password": "secret-db-password", "dsn": "dbhost:1521/FREEPDB1"}],
        )
        self.assertEqual(connection.fake_cursor.statements[0], "SET TRANSACTION READ ONLY")
        self.assertIn("UNBALANCED journal=M2-FX-one currency=USD debits=100.0000 credits=99.5000", output.getvalue())
        self.assertIn("UNGROUPED currency=EUR type=CREDIT count=2 amount=7.0000", output.getvalue())
        self.assertNotIn("secret-db-password", output.getvalue())

    def test_ungrouped_entries_are_reported_but_do_not_hide_balanced_grouped_journals(self):
        script = load_script("check_m2_ledger")
        connection = FakeConnection([], [("USD", "CREDIT", 1, "5.0000")])
        fake_oracledb = types.SimpleNamespace(connect=lambda **kwargs: connection)
        output = io.StringIO()
        with (
            mock.patch.object(sys, "argv", ["check_m2_ledger.py"]),
            mock.patch.dict(sys.modules, {"oracledb": fake_oracledb}),
            mock.patch.dict(
                script.os.environ,
                {
                    "ORACLE_JDBC_URL": "jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
                    "ORACLE_USERNAME": "fluxpay",
                    "ORACLE_PASSWORD": "password",
                },
                clear=True,
            ),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(script.main(), 0)

        self.assertIn("grouped journals balanced", output.getvalue())
        self.assertIn("UNGROUPED currency=USD type=CREDIT count=1 amount=5.0000", output.getvalue())


if __name__ == "__main__":
    unittest.main()
