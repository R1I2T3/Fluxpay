"""Behavioral tests for isolated M5 wrappers; these never contact Oracle."""
import importlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))


class M5ScriptsTest(unittest.TestCase):
    def module(self, name):
        try:
            return importlib.import_module(name)
        except ModuleNotFoundError:
            self.fail(f"M5 wrapper {name} has not been implemented")

    def test_env_file_overrides_inherited_values_without_executing_substitution(self):
        support = self.module("m5_support")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "private.env"
            path.write_text('ORACLE_USERNAME=FLUXPAY_M5_TEST\nJWT_SECRET="literal$(not-a-command)"\n')
            actual = support.load_environment(path, {"ORACLE_USERNAME": "FLUXPAY", "KEEP": "yes"})
            self.assertEqual("FLUXPAY_M5_TEST", actual["ORACLE_USERNAME"])
            self.assertEqual("literal$(not-a-command)", actual["JWT_SECRET"])
            self.assertEqual("yes", actual["KEEP"])

    def test_setup_gate_rejects_missing_optin_shared_and_mismatched_schema(self):
        support = self.module("m5_support")
        good = dict(M5_ALLOW_FIXTURE_SETUP="true", M5_TEST_SCHEMA="FLUXPAY_M5_TEST",
                    ORACLE_USERNAME="FLUXPAY_M5_TEST", ORACLE_PASSWORD="synthetic",
                    ORACLE_JDBC_URL="jdbc:oracle:thin:@//localhost:1521/FREEPDB1",
                    JWT_SECRET="x" * 40)
        support.validate_isolated_environment(good)
        for update in ({"M5_ALLOW_FIXTURE_SETUP": "false"}, {"M5_TEST_SCHEMA": ""},
                       {"ORACLE_USERNAME": "FLUXPAY"},
                       {"ORACLE_USERNAME": "SYSTEM", "M5_TEST_SCHEMA": "SYSTEM"}):
            with self.subTest(update=update), self.assertRaises(ValueError):
                support.validate_isolated_environment(good | update)

    def test_report_gate_requires_each_group_and_rejects_skipped_oracle(self):
        support = self.module("m5_support")
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for suffix in ("UnitTest", "WebTest", "ContractTest"):
                (root / f"TEST-com.fluxpay.M5Example{suffix}.xml").write_text(
                    f'<testsuite name="com.fluxpay.M5Example{suffix}" tests="2" failures="0" errors="0" skipped="0"/>')
            self.assertEqual(6, sum(support.check_reports(root, ["UnitTest", "WebTest", "ContractTest"]).values()))
            with self.assertRaises(RuntimeError):
                support.check_reports(root, ["OracleTest"])
            (root / "TEST-com.fluxpay.M5DbOracleTest.xml").write_text(
                '<testsuite name="com.fluxpay.M5DbOracleTest" tests="2" failures="0" errors="0" skipped="2"/>')
            with self.assertRaises(RuntimeError):
                support.check_reports(root, ["OracleTest"])

    def test_token_reader_never_accepts_repository_token_paths(self):
        support = self.module("m5_support")
        with self.assertRaises(ValueError):
            support.private_token_path(Path(__file__).resolve().parents[1] / "token.json")

    def test_managed_process_propagates_child_failure(self):
        support = self.module("m5_support")
        self.assertEqual(7, support.run_owned_process([sys.executable, "-c", "raise SystemExit(7)"], os.environ.copy()))

    def test_managed_process_interrupt_stops_its_child(self):
        support = self.module("m5_support")
        child = unittest.mock.Mock()
        child.wait.side_effect = [KeyboardInterrupt(), 0]
        child.poll.return_value = None
        with patch.object(support.subprocess, "Popen", return_value=child), patch.object(support, "stop_process") as stop:
            self.assertEqual(130, support.run_owned_process(["controlled-child"], {}))
            stop.assert_called_once_with(child)

    def test_unit_launcher_command_excludes_waiting_launcher_and_propagates_failure(self):
        runner = self.module("test_m5")
        with patch.object(runner, "run_owned_process", return_value=9) as run:
            self.assertEqual(9, runner.main(["--suite", "unit"]))
            command = run.call_args.args[0]
            self.assertTrue(any("M5*UnitTest,M5*WebTest,M5*ContractTest" in str(arg) for arg in command))
            self.assertFalse(any("m5.solo.launch=true" in str(arg) for arg in command))

    def test_startup_fails_before_spawning_without_explicit_schema(self):
        launcher = self.module("start_m5_solo")
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "private.env"
            path.write_text("ORACLE_USERNAME=FLUXPAY\n")
            with patch.object(launcher, "run_owned_process") as run, patch.dict(os.environ, {}, clear=True):
                self.assertEqual(2, launcher.main(["--env-file", str(path)]))
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
