"""Start the test-only M5 server on loopback against an explicit isolated schema."""
import argparse
from pathlib import Path
import tempfile

from m5_support import (PROJECT_ROOT, fail, load_environment, m5_maven_command,
                        private_token_path, run_owned_process, validate_isolated_environment)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8081)
    args = parser.parse_args(argv)
    try:
        env = load_environment(args.env_file)
        validate_isolated_environment(env)
        if not 1024 <= args.port <= 65535:
            raise ValueError("The loopback port must be between 1024 and 65535")
        if not env.get("M5_SOLO_TOKEN_FILE"):
            raise ValueError("M5_SOLO_TOKEN_FILE is required and must be outside version control")
        env["M5_SOLO_TOKEN_FILE"] = str(private_token_path(env["M5_SOLO_TOKEN_FILE"]))
        env["SERVER_PORT"] = str(args.port)
        with tempfile.TemporaryDirectory(prefix="fluxpay-m5-launch-") as directory:
            shutdown = Path(directory) / "stop"
            command = m5_maven_command("-Dtest=M5SoloLauncherTest", "-Dm5.solo.launch=true",
                                       "-Dm5.solo.shutdown-file=" + str(shutdown),
                                       "-Dm5.project.root=" + str(PROJECT_ROOT),
                                       "-DfailIfNoTests=true", "test")
            print(f"Starting M5 solo on http://127.0.0.1:{args.port}; Ctrl+C stops the owned application.")
            return run_owned_process(command, env, shutdown_file=shutdown)
    except (ValueError, OSError, RuntimeError) as error:
        return fail(str(error))


if __name__ == "__main__":
    raise SystemExit(main())
