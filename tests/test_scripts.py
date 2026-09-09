import importlib.util
import os
import subprocess
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
    def test_start_backend_stays_attached_after_readiness(self):
        script = load_script("start-backend")
        process = RunningProcess(wait_result=9)
        with (
            mock.patch.object(sys, "argv", ["start-backend.py"]),
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

    def test_start_backend_uses_windows_maven_wrapper_from_project_root(self):
        script = load_script("start-backend")
        with (
            mock.patch.object(sys, "argv", ["start-backend.py"]),
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
            mock.patch.object(sys, "argv", ["start-frontend.py"]),
            mock.patch.object(script.os.path, "isdir", return_value=True),
            mock.patch.object(script.subprocess, "run", return_value=CompletedProcess()) as run,
        ):
            self.assertEqual(script.main(), 0)

        command = run.call_args.args[0]
        self.assertEqual(Path(command[0]).name.lower(), "cmd.exe")
        self.assertEqual(command[1:3], ["/d", "/c"])
        self.assertEqual(Path(command[3]), PROJECT_ROOT / "frontend" / "fluxpay-ui" / "node_modules" / ".bin" / "ojet.cmd")
        self.assertEqual(run.call_args.kwargs["cwd"], PROJECT_ROOT / "frontend" / "fluxpay-ui")

    def test_test_all_uses_current_python_for_seed_script(self):
        script = load_script("test-all")
        with (
            mock.patch.object(sys, "argv", ["test-all.py", "--suite", "e2e"]),
            mock.patch.object(script.subprocess, "run", return_value=CompletedProcess()) as run,
            mock.patch("urllib.request.urlopen", return_value=HttpResponse()),
        ):
            self.assertEqual(script.main(), 0)

        seed_command = run.call_args_list[0].args[0]
        self.assertEqual(seed_command, [sys.executable, str(PROJECT_ROOT / "scripts" / "seed-demo.py")])

    def test_test_all_uses_windows_maven_wrapper(self):
        script = load_script("test-all")
        with (
            mock.patch.object(sys, "argv", ["test-all.py", "--suite", "backend"]),
            mock.patch.object(script.subprocess, "run", return_value=CompletedProcess()) as run,
        ):
            self.assertEqual(script.main(), 0)

        command = run.call_args.args[0]
        self.assertEqual(Path(command[0]).name.lower(), "cmd.exe")
        self.assertEqual(command[1:3], ["/d", "/c"])
        self.assertEqual(Path(command[3]), PROJECT_ROOT / "mvnw.cmd")

    def test_infrastructure_commands_run_from_project_root(self):
        start = load_script("start-infra")
        stop = load_script("stop-infra")
        with mock.patch.object(start.subprocess, "run", return_value=CompletedProcess()) as run:
            start.run(["docker", "compose", "up", "-d"])
        self.assertEqual(run.call_args.kwargs["cwd"], PROJECT_ROOT)

        with (
            mock.patch.object(sys, "argv", ["stop-infra.py"]),
            mock.patch.object(stop.subprocess, "run", return_value=CompletedProcess()) as run,
        ):
            self.assertEqual(stop.main(), 0)
        self.assertEqual(run.call_args.kwargs["cwd"], PROJECT_ROOT)


if __name__ == "__main__":
    unittest.main()
