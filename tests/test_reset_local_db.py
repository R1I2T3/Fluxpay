import contextlib
import importlib.util
import io
import os
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))


def load_script():
    path = SCRIPTS_DIR / "reset-local-db.py"
    spec = importlib.util.spec_from_file_location("reset_local_db", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeCursor:
    def __init__(self, target_exists=True):
        self.target_exists = target_exists
        self.statements = []
        self.current = ""

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def execute(self, statement, **params):
        self.current = " ".join(statement.split())
        self.statements.append((self.current, params))

    def fetchone(self):
        if "SYS_CONTEXT" in self.current:
            return ("SYSTEM", "FREE", "FREEPDB1", "fluxpay-oracle")
        if "FROM all_users" in self.current:
            return (1 if self.target_exists else 0,)
        raise AssertionError(f"unexpected fetch for {self.current}")


class FakeConnection:
    def __init__(self, target_exists=True):
        self.cursor_instance = FakeCursor(target_exists)
        self.committed = False

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def cursor(self):
        return self.cursor_instance

    def commit(self):
        self.committed = True


class ResetLocalDbTests(unittest.TestCase):
    def test_compose_topic_reset_deletes_only_fluxpay_inventory(self):
        script = load_script()
        commands = []

        def run(command, **kwargs):
            commands.append(command)
            if "--list" in command:
                return subprocess.CompletedProcess(
                    command,
                    0,
                    stdout="payment.initiated\nunrelated.orders\npayout.recovery.dlt\n",
                    stderr="",
                )
            return subprocess.CompletedProcess(command, 0, stdout="", stderr="")

        self.assertEqual(len(script.FLUXPAY_TOPICS), 11)
        self.assertEqual(
            script.FLUXPAY_TOPICS[5:8],
            ("payout.failed", "payout.retry", "payout.refund"),
        )
        with mock.patch.object(script.subprocess, "run", side_effect=run):
            removed = script.reset_compose_topics()

        self.assertEqual(removed, ["payment.initiated", "payout.recovery.dlt"])
        deletes = [command for command in commands if "--delete" in command]
        deleted_arguments = " ".join(" ".join(command) for command in deletes)
        self.assertIn("payment.initiated", deleted_arguments)
        self.assertIn("payout.recovery.dlt", deleted_arguments)
        self.assertNotIn("unrelated.orders", deleted_arguments)
        created = [command[command.index("--topic") + 1] for command in commands if "--create" in command]
        self.assertEqual(len(created), 11)
        self.assertIn("payout.retry", created)
        self.assertIn("payout.refund", created)
        self.assertNotIn("--volumes", " ".join(" ".join(command) for command in commands))

    def test_dry_run_resolves_metadata_without_executing_destructive_sql(self):
        script = load_script()
        connection = FakeConnection()
        output = io.StringIO()
        with (
            mock.patch.object(sys, "argv", ["reset-local-db.py", "--schema", "FLUXPAY"]),
            mock.patch.object(script, "load_env"),
            mock.patch.dict(
                os.environ,
                {
                    "ORACLE_ADMIN_JDBC_URL": "jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
                    "ORACLE_ADMIN_USERNAME": "SYSTEM",
                    "ORACLE_ADMIN_PASSWORD": "admin-secret",
                    "ORACLE_PASSWORD": "app-secret",
                },
                clear=True,
            ),
            mock.patch.object(script, "connect_admin", return_value=connection),
            contextlib.redirect_stdout(output),
        ):
            self.assertEqual(script.main(), 0)

        statements = [statement for statement, _ in connection.cursor_instance.statements]
        self.assertTrue(any("SYS_CONTEXT" in statement for statement in statements))
        self.assertFalse(any("DROP USER" in statement or "CREATE USER" in statement for statement in statements))
        self.assertFalse(connection.committed)
        self.assertIn("DRY RUN", output.getvalue())
        self.assertNotIn("admin-secret", output.getvalue())
        self.assertNotIn("app-secret", output.getvalue())

    def test_remote_connections_are_rejected_before_connecting(self):
        script = load_script()
        with (
            mock.patch.object(
                sys,
                "argv",
                ["reset-local-db.py", "--schema", "FLUXPAY", "--execute"],
            ),
            mock.patch.object(script, "load_env"),
            mock.patch.dict(
                os.environ,
                {
                    "ORACLE_ADMIN_JDBC_URL": "jdbc:oracle:thin:@//db.example.com:1521/PROD",
                    "ORACLE_ADMIN_USERNAME": "SYSTEM",
                    "ORACLE_ADMIN_PASSWORD": "secret",
                    "ORACLE_PASSWORD": "secret",
                },
                clear=True,
            ),
            mock.patch.object(script, "connect_admin") as connect,
        ):
            self.assertEqual(script.main(), 2)
        connect.assert_not_called()

    def test_only_named_fluxpay_schemas_are_valid_targets(self):
        script = load_script()
        for target in ("SYS", "SYSTEM", "OTHER_APP", "FLUXPAY_ARCHIVE"):
            with self.subTest(target=target), self.assertRaisesRegex(ValueError, "authorized"):
                script.validate_target(target)
        self.assertEqual(script.validate_target("fluxpay_test"), "FLUXPAY_TEST")

    def test_execute_checks_connection_and_target_before_drop_then_recreates_grants(self):
        script = load_script()
        connection = FakeConnection()
        script.reset_schema(connection, "FLUXPAY_TEST", "test-secret")

        statements = [statement for statement, _ in connection.cursor_instance.statements]
        metadata_index = next(i for i, statement in enumerate(statements) if "SYS_CONTEXT" in statement)
        target_index = next(i for i, statement in enumerate(statements) if "FROM all_users" in statement)
        drop_index = next(i for i, statement in enumerate(statements) if "DROP USER FLUXPAY_TEST CASCADE" in statement)
        create_index = next(i for i, statement in enumerate(statements) if "CREATE USER FLUXPAY_TEST" in statement)
        self.assertLess(metadata_index, drop_index)
        self.assertLess(target_index, drop_index)
        self.assertLess(drop_index, create_index)
        self.assertTrue(any("GRANT CREATE SESSION" in statement for statement in statements))
        self.assertTrue(connection.committed)


if __name__ == "__main__":
    unittest.main()
