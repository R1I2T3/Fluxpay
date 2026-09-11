#!/usr/bin/env python3
"""Run Spring Boot with .env propagated; wait for docs endpoint."""

import argparse, os, subprocess, sys, time, urllib.request
from pathlib import Path

from platform_commands import PROJECT_ROOT, maven_command, project_path


def load_env(path):
    path = project_path(path)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as env_file:
            for line in env_file:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    k, v = line.split("=", 1)
                    os.environ.setdefault(k, v)


def configure_maven_home():
    """Keep Maven Wrapper out of C:\\.m2 when HOME is unset or empty on Windows."""
    if not os.environ.get("MAVEN_USER_HOME"):
        user_profile = os.environ.get("USERPROFILE") or str(Path.home())
        os.environ["MAVEN_USER_HOME"] = str(Path(user_profile) / ".m2")
    if not os.environ.get("HOME"):
        os.environ["HOME"] = os.environ.get("USERPROFILE") or str(Path.home())


def terminate_process(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int)
    ap.add_argument("--profile", default="local")
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    load_env(a.env_file)
    configure_maven_home()
    port = a.port if a.port is not None else int(os.environ.get("SERVER_PORT", "8080"))
    os.environ["SERVER_PORT"] = str(port)
    p = subprocess.Popen(
        maven_command(
            f"-Duser.home={os.environ['HOME']}",
            "-f",
            "backend/pom.xml",
            "spring-boot:run",
            f"-Dspring-boot.run.profiles={a.profile}",
        ),
        cwd=PROJECT_ROOT,
    )
    try:
        for _ in range(30):
            returncode = p.poll()
            if returncode is not None:
                print(f"backend exited before readiness (code {returncode})")
                return returncode
            time.sleep(2)
            try:
                with urllib.request.urlopen(f"http://localhost:{port}/v3/api-docs", timeout=2) as r:
                    if r.status == 200:
                        print("backend UP; press Ctrl+C to stop")
                        return p.wait()
            except Exception as e:
                if a.verbose:
                    print("waiting backend...", e)
        terminate_process(p)
        print("backend timeout")
        return 2
    except KeyboardInterrupt:
        print("stopping backend...")
        terminate_process(p)
        return 130


if __name__ == "__main__":
    sys.exit(main())
