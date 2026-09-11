#!/usr/bin/env python3
"""Gate: mvn verify + ojet build + seed + smoke(login, 1 msg per topic)."""

import argparse, os, shutil, subprocess, sys

from platform_commands import FRONTEND_DIR, PROJECT_ROOT, maven_command, ojet_command, python_command


def kafka_script(name):
    """Locate a bare-metal Kafka CLI script; prefer KAFKA_HOME, then PATH."""
    home = os.environ.get("KAFKA_HOME", "")
    suffix = ".bat" if sys.platform == "win32" else ".sh"
    candidates = []
    if home:
        if sys.platform == "win32":
            candidates.append(os.path.join(home, "bin", "windows", name + suffix))
        candidates.append(os.path.join(home, "bin", name + suffix))
    found = shutil.which(name + suffix) or shutil.which(name)
    if found:
        candidates.append(found)
    for candidate in candidates:
        if candidate and os.path.exists(candidate):
            return candidate
    return None


def run(cmd, cwd=PROJECT_ROOT):
    print("+", " ".join(cmd))
    r = subprocess.run(cmd, cwd=cwd)
    return r.returncode


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--suite", default="all", choices=["all", "backend", "frontend", "e2e"])
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    if a.suite in ("all", "backend"):
        if run(maven_command("-f", "backend/pom.xml", "verify")):
            print("backend FAIL")
            return 3
        if os.environ.get("KAFKA_BOOTSTRAP_SERVERS") and os.environ.get("ORACLE_JDBC_URL"):
            if run(maven_command("-f", "backend/pom.xml", "-Dtest=PaymentEventPersistenceIT", "test")):
                print("persistence IT FAIL")
                return 3
        else:
            print("persistence IT skipped (KAFKA_BOOTSTRAP_SERVERS/ORACLE_JDBC_URL unset)")
    if a.suite in ("all", "frontend"):
        if run(ojet_command("build"), cwd=FRONTEND_DIR):
            print("frontend FAIL")
            return 3
    if a.suite in ("all", "e2e"):
        if run(python_command("scripts/seed-demo.py")):
            print("seed FAIL")
            return 3
        import urllib.request, json

        try:
            req = urllib.request.Request(
                "http://localhost:8080/api/auth/login",
                data=json.dumps({"email": "alice@demo.io", "password": "Pass123!"}).encode(),
                headers={"Content-Type": "application/json"},
            )
            urllib.request.urlopen(req, timeout=5).read()
            print("smoke login OK")
        except Exception as e:
            print("smoke login FAIL", e)
            return 3
        topics = [
            "payment.initiated",
            "payment.route.selected",
            "payment.screening.completed",
            "payout.submitted",
            "payout.failed",
            "payout.completed",
            "payment.refunded",
        ]
        producer = kafka_script("kafka-console-producer")
        if producer is None:
            print("produce SKIP kafka-console-producer.sh not found; set KAFKA_HOME")
            return 0
        bootstrap = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
        for t in topics:
            r1 = subprocess.run(
                [
                    producer,
                    "--bootstrap-server",
                    bootstrap,
                    "--topic",
                    t,
                ],
                input=b"smoke-1\n",
                capture_output=True,
                timeout=15,
            )
            if r1.returncode != 0:
                print("produce FAIL", t)
                return 3
        print("smoke kafka OK 7/7")
    print("ALL GREEN")
    return 0


if __name__ == "__main__":
    sys.exit(main())
