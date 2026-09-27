#!/usr/bin/env python3
"""Gate: mvn verify + ojet build + seed + smoke(login, 1 msg per topic)."""

import argparse
import os
import subprocess
import sys

from platform_commands import (
    FRONTEND_DIR,
    PROJECT_ROOT,
    configure_windows_maven_home,
    load_env,
    maven_command,
    ojet_command,
    python_command,
)


def run(cmd, cwd=PROJECT_ROOT, env=None):
    print("+", " ".join(cmd))
    r = subprocess.run(cmd, cwd=cwd, env=env, check=False)
    return r.returncode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", default="all", choices=["all", "backend", "frontend", "e2e"])
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    caller_env = os.environ.copy()
    load_env(a.env_file)
    configure_windows_maven_home()
    if a.suite in ("all", "backend"):
        integration_requested = os.environ.get("ORACLE_TESTS_ACTIVE", "").lower() == "true"
        if integration_requested:
            required = (
                "KAFKA_BOOTSTRAP_SERVERS",
                "ORACLE_TEST_JDBC_URL",
                "ORACLE_TEST_USERNAME",
                "ORACLE_TEST_PASSWORD",
            )
            missing = [name for name in required if not os.environ.get(name)]
            if missing:
                print("backend integration FAIL missing " + ", ".join(missing))
                return 3
            if os.environ["ORACLE_TEST_USERNAME"].upper() != "FLUXPAY_TEST":
                print("backend integration FAIL requires ORACLE_TEST_USERNAME=FLUXPAY_TEST")
                return 3
        command = ["-f", "backend/pom.xml"]
        if integration_requested:
            command.append("-Pintegration")
        command.append("verify")
        maven_env = None
        if integration_requested:
            maven_env = caller_env.copy()
            for name in (
                "MAVEN_USER_HOME",
                "MAVEN_OPTS",
                "ORACLE_TESTS_ACTIVE",
                *required,
            ):
                if name in os.environ:
                    maven_env[name] = os.environ[name]
        if run(maven_command(*command), env=maven_env):
            print("backend FAIL")
            return 3
        if integration_requested:
            print("backend unit+integration PASS")
        else:
            print("backend unit PASS; integration SKIP (ORACLE_TESTS_ACTIVE is not true)")
    if a.suite in ("all", "frontend") and run(ojet_command("build"), cwd=FRONTEND_DIR):
        print("frontend FAIL")
        return 3
    if a.suite in ("all", "e2e"):
        if run(python_command("scripts/seed-local.py")):
            print("seed FAIL")
            return 3
        if run(python_command("scripts/smoke-local.py")):
            print("smoke FAIL")
            return 3
    print("requested suites PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
