import importlib.util
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PROJECT_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS_DIR = PROJECT_ROOT / "scripts"
sys.path.insert(0, str(SCRIPTS_DIR))


def load_script(name):
    path = SCRIPTS_DIR / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CompletedProcess:
    returncode = 0


class RunningProcess:
    def __init__(self, poll_result=None, wait_result=0, interrupt_on_wait=False):
        self.poll_result = poll_result
        self.wait_result = wait_result
        self.interrupt_on_wait = interrupt_on_wait
        self.wait_calls = 0
        self.terminated = False
        self.killed = False

    def poll(self):
        return self.poll_result

    def wait(self, timeout=None):
        self.wait_calls += 1
        if self.interrupt_on_wait and self.wait_calls == 1:
            raise KeyboardInterrupt
        return self.wait_result

    def terminate(self):
        self.terminated = True

    def kill(self):
        self.killed = True


class HttpResponse:
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        return False

    def read(self):
        return b"{}"


class ScriptCommandTests(unittest.TestCase):
    def test_compose_infrastructure_starts_named_services_and_creates_topics_in_container(self):
        script = load_script("start-infra")
        commands = []

        def run(command, verbose=False):
            commands.append(command)
            return CompletedProcess()

        with (
            mock.patch.object(sys, "argv", ["start-infra.py", "--mode", "compose"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script, "run", side_effect=run),
            mock.patch.object(script, "wait_port", return_value=True),
        ):
            self.assertEqual(script.main(), 0)

        self.assertEqual(commands[0], ["docker", "compose", "up", "-d", "oracle", "kafka"])
        topic_commands = [command for command in commands if "--create" in command]
        self.assertEqual(len(topic_commands), 11)
        self.assertTrue(all(command[:5] == ["docker", "compose", "exec", "-T", "kafka"] for command in topic_commands))
        created_topics = [command[command.index("--topic") + 1] for command in topic_commands]
        self.assertEqual(
            created_topics,
            [
                "payment.initiated",
                "payment.route.selected",
                "payment.screening.completed",
                "payment.review.requested",
                "payout.submitted",
                "payout.failed",
                "payout.retry",
                "payout.refund",
                "payout.completed",
                "payment.refunded",
                "payout.recovery.dlt",
            ],
        )
        self.assertIn("payment.review.requested", created_topics)
        self.assertIn("payout.retry", created_topics)
        self.assertIn("payout.refund", created_topics)
        self.assertIn("payout.recovery.dlt", created_topics)

    def test_external_infrastructure_is_probed_but_never_started_or_stopped(self):
        start = load_script("start-infra")
        stop = load_script("stop-infra")
        start_commands = []

        def run(command, verbose=False):
            start_commands.append(command)
            return CompletedProcess()

        with (
            mock.patch.object(sys, "argv", ["start-infra.py", "--mode", "external", "--skip-topics"]),
            mock.patch.object(start, "load_env"),
            mock.patch.object(start, "run", side_effect=run),
            mock.patch.object(start, "wait_port", return_value=True),
        ):
            self.assertEqual(start.main(), 0)

        self.assertFalse(any(command[:3] == ["docker", "compose", "up"] for command in start_commands))
        with (
            mock.patch.object(sys, "argv", ["stop-infra.py", "--mode", "external"]),
            mock.patch.object(stop, "load_env"),
            mock.patch.object(stop.subprocess, "run") as stopped,
        ):
            self.assertEqual(stop.main(), 0)
        stopped.assert_not_called()

    def test_compose_start_with_both_services_skipped_does_not_start_everything(self):
        script = load_script("start-infra")
        with (
            mock.patch.object(
                sys,
                "argv",
                ["start-infra.py", "--mode", "compose", "--skip-oracle", "--skip-kafka"],
            ),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script, "run") as run,
        ):
            self.assertEqual(script.main(), 0)
        run.assert_not_called()

    def test_start_backend_stays_attached_after_readiness(self):
        script = load_script("start-backend")
        process = RunningProcess(wait_result=9)
        with (
            mock.patch.object(sys, "argv", ["start-backend.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "Popen", return_value=process),
            mock.patch.object(script.time, "sleep"),
            mock.patch.object(script.urllib.request, "urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 9)

        self.assertEqual(process.wait_calls, 1)

    def test_start_backend_returns_if_maven_exits_before_readiness(self):
        script = load_script("start-backend")
        process = RunningProcess(poll_result=4)
        with (
            mock.patch.object(sys, "argv", ["start-backend.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "Popen", return_value=process),
            mock.patch.object(script.time, "sleep"),
            mock.patch.object(script.urllib.request, "urlopen", side_effect=OSError("not ready")),
        ):
            self.assertEqual(script.main(), 4)

        self.assertFalse(process.terminated)

    def test_start_backend_terminates_child_on_keyboard_interrupt(self):
        script = load_script("start-backend")
        process = RunningProcess(interrupt_on_wait=True)
        with (
            mock.patch.object(sys, "argv", ["start-backend.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "Popen", return_value=process),
            mock.patch.object(script.time, "sleep"),
            mock.patch.object(script.urllib.request, "urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 130)

        self.assertTrue(process.terminated)

    def test_start_backend_uses_port_from_env_file(self):
        script = load_script("start-backend")
        requested_urls = []

        def urlopen(url, timeout):
            requested_urls.append(url)
            return HttpResponse()

        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text("SERVER_PORT=8083\n", encoding="utf-8")
            with (
                mock.patch.dict(script.os.environ),
                mock.patch.object(sys, "argv", ["start-backend.py", "--env-file", str(env_file)]),
                mock.patch.object(script.subprocess, "Popen", return_value=RunningProcess()),
                mock.patch.object(script.time, "sleep"),
                mock.patch.object(script.urllib.request, "urlopen", side_effect=urlopen),
            ):
                script.os.environ.pop("SERVER_PORT", None)
                self.assertEqual(script.main(), 0)
                selected_port = script.os.environ["SERVER_PORT"]

        self.assertEqual(selected_port, "8083")
        self.assertEqual(requested_urls, ["http://localhost:8083/v3/api-docs"])

    def test_start_backend_does_not_activate_a_profile_by_default(self):
        script = load_script("start-backend")
        with (
            mock.patch.object(sys, "argv", ["start-backend.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "Popen", return_value=RunningProcess()) as popen,
            mock.patch.object(script.time, "sleep"),
            mock.patch.object(script.urllib.request, "urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 0)

        command = popen.call_args.args[0]
        self.assertFalse(any(arg.startswith("-Dspring-boot.run.profiles=") for arg in command))

    def test_start_backend_passes_an_explicit_profile(self):
        script = load_script("start-backend")
        with (
            mock.patch.object(sys, "argv", ["start-backend.py", "--profile", "development"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "Popen", return_value=RunningProcess()) as popen,
            mock.patch.object(script.time, "sleep"),
            mock.patch.object(script.urllib.request, "urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 0)

        self.assertIn("-Dspring-boot.run.profiles=development", popen.call_args.args[0])

    def test_start_backend_sets_windows_maven_homes_from_userprofile(self):
        script = load_script("start-backend")
        with (
            mock.patch.dict(
                script.os.environ,
                {"USERPROFILE": r"C:\Users\Ritesh Jha"},
                clear=True,
            ),
            mock.patch.object(script.sys, "platform", "win32"),
            mock.patch.object(sys, "argv", ["start-backend.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "Popen", return_value=RunningProcess()),
            mock.patch.object(script.time, "sleep"),
            mock.patch.object(script.urllib.request, "urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 0)
            self.assertEqual(script.os.environ["MAVEN_USER_HOME"], r"C:\Users\Ritesh Jha\.m2")
            self.assertEqual(script.os.environ["MAVEN_OPTS"], r'-Duser.home="C:\Users\Ritesh Jha"')

    def test_start_backend_uses_windows_maven_wrapper_from_project_root(self):
        script = load_script("start-backend")
        with (
            mock.patch.object(script.sys, "platform", "win32"),
            mock.patch.object(sys, "argv", ["start-backend.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "Popen", return_value=RunningProcess()) as popen,
            mock.patch.object(script.time, "sleep"),
            mock.patch.object(script.urllib.request, "urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 0)

        command = popen.call_args.args[0]
        self.assertEqual(Path(command[0]).name.lower(), "cmd.exe")
        self.assertEqual(command[1:3], ["/d", "/c"])
        self.assertEqual(Path(command[3]), PROJECT_ROOT / "mvnw.cmd")
        self.assertEqual(popen.call_args.kwargs["cwd"], PROJECT_ROOT)

    def test_start_backend_uses_unix_maven_wrapper_from_project_root(self):
        script = load_script("start-backend")
        with (
            mock.patch("platform_commands.sys.platform", "linux"),
            mock.patch.object(sys, "argv", ["start-backend.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "Popen", return_value=RunningProcess()) as popen,
            mock.patch.object(script.time, "sleep"),
            mock.patch.object(script.urllib.request, "urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 0)

        command = popen.call_args.args[0]
        self.assertEqual(command[0], str(PROJECT_ROOT / "mvnw"))
        self.assertEqual(popen.call_args.kwargs["cwd"], PROJECT_ROOT)

    def test_start_frontend_uses_project_local_windows_ojet_wrapper(self):
        script = load_script("start-frontend")
        with (
            mock.patch.object(script.sys, "platform", "win32"),
            mock.patch.object(sys, "argv", ["start-frontend.py"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.os.path, "isdir", return_value=True),
            mock.patch.object(script.subprocess, "run", return_value=CompletedProcess()) as run,
        ):
            self.assertEqual(script.main(), 0)

        command = run.call_args.args[0]
        self.assertEqual(Path(command[0]).name.lower(), "cmd.exe")
        self.assertEqual(command[1:3], ["/d", "/c"])
        self.assertEqual(
            Path(command[3]), PROJECT_ROOT / "frontend" / "fluxpay-ui" / "node_modules" / ".bin" / "ojet.cmd"
        )
        self.assertEqual(run.call_args.kwargs["cwd"], PROJECT_ROOT / "frontend" / "fluxpay-ui")

    def test_test_all_uses_current_python_for_local_seed_script(self):
        script = load_script("test-all")
        with (
            mock.patch.object(sys, "argv", ["test-all.py", "--suite", "e2e"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "run", return_value=CompletedProcess()) as run,
            mock.patch("urllib.request.urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 0)

        seed_command = run.call_args_list[0].args[0]
        self.assertEqual(seed_command, [sys.executable, str(PROJECT_ROOT / "scripts" / "seed-local.py")])
        smoke_command = run.call_args_list[1].args[0]
        self.assertEqual(smoke_command, [sys.executable, str(PROJECT_ROOT / "scripts" / "smoke-local.py")])

    def test_test_all_uses_windows_maven_wrapper(self):
        script = load_script("test-all")
        with (
            mock.patch.object(script.sys, "platform", "win32"),
            mock.patch.dict(
                script.os.environ,
                {"COMSPEC": os.environ.get("COMSPEC", "cmd.exe")},
                clear=True,
            ),
            mock.patch.object(sys, "argv", ["test-all.py", "--suite", "backend"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "run", return_value=CompletedProcess()) as run,
        ):
            self.assertEqual(script.main(), 0)

        command = run.call_args.args[0]
        self.assertEqual(Path(command[0]).name.lower(), "cmd.exe")
        self.assertEqual(command[1:3], ["/d", "/c"])
        self.assertEqual(Path(command[3]), PROJECT_ROOT / "mvnw.cmd")

    def test_test_all_loads_env_file_before_backend_subprocess(self):
        script = load_script("test-all")
        observed_settings = []

        def run(command, cwd, env=None, check=False):
            observed_settings.append(
                (
                    command,
                    script.os.environ.get("KAFKA_BOOTSTRAP_SERVERS"),
                    script.os.environ.get("ORACLE_TESTS_ACTIVE"),
                    script.os.environ.get("ORACLE_TEST_JDBC_URL"),
                    script.os.environ.get("ORACLE_TEST_USERNAME"),
                    env,
                )
            )
            return CompletedProcess()

        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "backend.env"
            env_file.write_text(
                "KAFKA_BOOTSTRAP_SERVERS=kafka.test:9092\n"
                "ORACLE_TESTS_ACTIVE=true\n"
                "ORACLE_TEST_JDBC_URL=jdbc:oracle:thin:@//db.test:1521/FREEPDB1\n"
                "ORACLE_TEST_USERNAME=FLUXPAY_TEST\n"
                "ORACLE_TEST_PASSWORD=test-secret\n"
                "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true\n"
                "FLUXPAY_DEVELOPMENT_SIMULATED_COMPLIANCE_ENABLED=true\n"
                "JWT_SECRET=demo-only-secret\n"
                "SERVER_PORT=8083\n",
                encoding="utf-8",
            )
            with (
                mock.patch.dict(
                    script.os.environ,
                    {
                        "COMSPEC": os.environ.get("COMSPEC", "cmd.exe"),
                        "PATH": "tool-path",
                        "MAVEN_USER_HOME": "test-maven-home",
                        "MAVEN_OPTS": "-Duser.home=test-user",
                    },
                    clear=True,
                ),
                mock.patch.object(
                    sys,
                    "argv",
                    ["test-all.py", "--suite", "backend", "--env-file", str(env_file)],
                ),
                mock.patch.object(script.subprocess, "run", side_effect=run),
            ):
                self.assertEqual(script.main(), 0)

        self.assertEqual(len(observed_settings), 1)
        self.assertEqual(
            observed_settings[0][1:5],
            (
                "kafka.test:9092",
                "true",
                "jdbc:oracle:thin:@//db.test:1521/FREEPDB1",
                "FLUXPAY_TEST",
            ),
        )
        self.assertIn("-Pintegration", observed_settings[0][0])
        self.assertEqual(observed_settings[0][0][-1], "verify")
        child_env = observed_settings[0][5]
        self.assertIsNotNone(child_env)
        self.assertEqual(child_env["PATH"], "tool-path")
        self.assertEqual(child_env["MAVEN_USER_HOME"], "test-maven-home")
        self.assertEqual(child_env["MAVEN_OPTS"], "-Duser.home=test-user")
        self.assertEqual(child_env["ORACLE_TESTS_ACTIVE"], "true")
        self.assertEqual(child_env["KAFKA_BOOTSTRAP_SERVERS"], "kafka.test:9092")
        self.assertEqual(child_env["ORACLE_TEST_JDBC_URL"], "jdbc:oracle:thin:@//db.test:1521/FREEPDB1")
        self.assertEqual(child_env["ORACLE_TEST_USERNAME"], "FLUXPAY_TEST")
        self.assertEqual(child_env["ORACLE_TEST_PASSWORD"], "test-secret")
        for name in (
            "FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED",
            "FLUXPAY_DEVELOPMENT_SIMULATED_COMPLIANCE_ENABLED",
            "JWT_SECRET",
            "SERVER_PORT",
        ):
            self.assertNotIn(name, child_env)

    def test_test_all_fails_when_requested_integration_credentials_are_incomplete(self):
        script = load_script("test-all")
        with (
            tempfile.TemporaryDirectory() as directory,
            mock.patch.dict(
                script.os.environ,
                {
                    "ORACLE_TESTS_ACTIVE": "true",
                    "KAFKA_BOOTSTRAP_SERVERS": "kafka.test:9092",
                },
                clear=True,
            ),
            mock.patch.object(
                sys,
                "argv",
                ["test-all.py", "--suite", "backend", "--env-file", str(Path(directory) / "missing.env")],
            ),
            mock.patch.object(script.subprocess, "run", return_value=CompletedProcess()) as run,
        ):
            self.assertEqual(script.main(), 3)

        self.assertEqual(run.call_count, 0)

    def test_test_all_rejects_application_schema_for_requested_integration(self):
        script = load_script("test-all")
        with (
            mock.patch.dict(
                script.os.environ,
                {
                    "ORACLE_TESTS_ACTIVE": "true",
                    "KAFKA_BOOTSTRAP_SERVERS": "kafka.test:9092",
                    "ORACLE_TEST_JDBC_URL": "jdbc:oracle:thin:@//db.test:1521/FREEPDB1",
                    "ORACLE_TEST_USERNAME": "FLUXPAY",
                    "ORACLE_TEST_PASSWORD": "test-secret",
                },
                clear=True,
            ),
            mock.patch.object(sys, "argv", ["test-all.py", "--suite", "backend"]),
            mock.patch.object(script, "load_env"),
            mock.patch.object(script.subprocess, "run", return_value=CompletedProcess()) as run,
        ):
            self.assertEqual(script.main(), 3)

        self.assertEqual(run.call_count, 0)

    def test_test_all_sets_windows_maven_homes_before_backend_subprocess(self):
        script = load_script("test-all")
        observed_settings = []

        def run(command, cwd, env=None, check=False):
            observed_settings.append(
                (
                    script.os.environ.get("MAVEN_USER_HOME"),
                    script.os.environ.get("MAVEN_OPTS"),
                )
            )
            return CompletedProcess()

        with (
            tempfile.TemporaryDirectory() as directory,
            mock.patch.dict(
                script.os.environ,
                {
                    "COMSPEC": os.environ.get("COMSPEC", "cmd.exe"),
                    "USERPROFILE": r"C:\Users\Ritesh Jha",
                },
                clear=True,
            ),
            mock.patch.object(script.sys, "platform", "win32"),
            mock.patch.object(
                sys,
                "argv",
                [
                    "test-all.py",
                    "--suite",
                    "backend",
                    "--env-file",
                    str(Path(directory) / "missing.env"),
                ],
            ),
            mock.patch.object(script.subprocess, "run", side_effect=run),
        ):
            self.assertEqual(script.main(), 0)

        self.assertEqual(
            observed_settings,
            [(r"C:\Users\Ritesh Jha\.m2", r'-Duser.home="C:\Users\Ritesh Jha"')],
        )

    def test_stop_infra_loads_env_file_before_subprocess(self):
        script = load_script("stop-infra")
        observed_profile = []

        def run(command, cwd):
            observed_profile.append(os.environ.get("COMPOSE_PROFILES"))
            return CompletedProcess()

        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / "infra.env"
            env_file.write_text("COMPOSE_PROFILES=database\n", encoding="utf-8")
            with (
                mock.patch.dict(os.environ, {}, clear=True),
                mock.patch.object(
                    sys,
                    "argv",
                    ["stop-infra.py", "--env-file", str(env_file)],
                ),
                mock.patch.object(script.subprocess, "run", side_effect=run),
            ):
                self.assertEqual(script.main(), 0)

        self.assertEqual(observed_profile, ["database"])

    def test_infrastructure_commands_run_from_project_root(self):
        start = load_script("start-infra")
        stop = load_script("stop-infra")
        with mock.patch.object(start.subprocess, "run", return_value=CompletedProcess()) as run:
            start.run(["docker", "compose", "up", "-d"])
        self.assertEqual(run.call_args.kwargs["cwd"], PROJECT_ROOT)

        with (
            tempfile.TemporaryDirectory() as directory,
            mock.patch.object(
                sys,
                "argv",
                ["stop-infra.py", "--env-file", str(Path(directory) / "missing.env"), "--mode", "compose"],
            ),
            mock.patch.object(stop.subprocess, "run", return_value=CompletedProcess()) as run,
        ):
            self.assertEqual(stop.main(), 0)
        self.assertEqual(run.call_args.kwargs["cwd"], PROJECT_ROOT)


if __name__ == "__main__":
    unittest.main()
