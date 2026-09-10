#!/usr/bin/env python3
"""Up Oracle (Docker on Linux) and bare-metal KRaft Kafka, probe readiness, create 7 topics."""

import argparse, os, shutil, socket, subprocess, sys, time

from platform_commands import PROJECT_ROOT, project_path

TOPICS = [
    "payment.initiated",
    "payment.route.selected",
    "payment.screening.completed",
    "payout.submitted",
    "payout.failed",
    "payout.completed",
    "payment.refunded",
    "payout.recovery.dlt",
]

DEFAULT_BOOTSTRAP = "localhost:9092"


def load_env(path):
    path = project_path(path)
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k, v)


def run(cmd, verbose=False):
    if verbose:
        print("+", " ".join(cmd))
    return subprocess.run(cmd, cwd=PROJECT_ROOT, capture_output=not verbose)


def split_host_port(value, default_port):
    """Split a host:port bootstrap value without third-party parsing."""
    host, sep, port = value.rpartition(":")
    if not sep or not host or not port.isdigit():
        return "localhost", default_port
    return host.strip("[]"), int(port)


def kafka_script(name):
    """Locate a bare-metal Kafka CLI script; prefer KAFKA_HOME, then PATH."""
    home = os.environ.get("KAFKA_HOME", "")
    suffix = ".bat" if sys.platform == "win32" else ".sh"
    candidates = []
    if home:
        # Linux: $KAFKA_HOME/bin/kafka-topics.sh, Windows: %KAFKA_HOME%\bin\windows\kafka-topics.bat
        # or %KAFKA_HOME%\bin\kafka-topics.bat depending on distribution layout.
        candidates.append(os.path.join(home, "bin", name + suffix))
        if sys.platform == "win32":
            candidates.append(os.path.join(home, "bin", "windows", name + ".bat"))
        else:
            candidates.append(os.path.join(home, "bin", "windows", name + suffix))
    found = shutil.which(name + suffix) or shutil.which(name)
    if found:
        candidates.append(found)
    for candidate in candidates:
        if candidate and os.path.exists(candidate):
            return candidate
    return None


def wait_port(host, port, timeout=30, verbose=False):
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            socket.create_connection((host, port), timeout=2).close()
            return True
        except OSError:
            if verbose:
                print(f"waiting {host}:{port}...")
            time.sleep(2)
    return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--skip-oracle", action="store_true")
    ap.add_argument("--skip-kafka", action="store_true")
    ap.add_argument("--env-file", default=".env")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()
    load_env(a.env_file)
    if not a.skip_oracle:
        r = run(["docker", "compose", "up", "-d"], verbose=a.verbose)
        if r.returncode != 0:
            print("compose up failed")
            return 1
    if not a.skip_oracle:
        try:
            import oracledb

            url = os.environ.get("ORACLE_JDBC_URL", "")
            user = os.environ.get("ORACLE_USERNAME", "fluxpay")
            pw = os.environ.get("ORACLE_PASSWORD", "fluxpay_dev_123")
            host = "localhost"
            port = 1521
            if not wait_port(host, port, timeout=120, verbose=a.verbose):
                print("oracle port timeout")
                return 2
            print("oracle port OK")
        except ImportError:
            print("oracledb not installed; port check only")
            wait_port("localhost", 1521, timeout=120, verbose=a.verbose)
    if not a.skip_kafka:
        # Kafka-owned bare-metal KRaft block: no Docker Kafka commands here.
        bootstrap = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", DEFAULT_BOOTSTRAP)
        kafka_host, kafka_port = split_host_port(bootstrap, 9092)
        topics_script = kafka_script("kafka-topics")
        if topics_script is None:
            print("kafka-topics.sh not found; set KAFKA_HOME to a bare-metal Kafka install")
            return 2
        if not wait_port(kafka_host, kafka_port, timeout=60, verbose=a.verbose):
            print("kafka port timeout")
            return 2
        probe = run(
            [topics_script, "--bootstrap-server", bootstrap, "--list"],
            verbose=a.verbose,
        )
        if probe.returncode != 0:
            print("kafka bootstrap probe failed")
            return 2
        ok = 0
        for t in TOPICS:
            r = run(
                [
                    topics_script,
                    "--create",
                    "--if-not-exists",
                    "--topic",
                    t,
                    "--bootstrap-server",
                    bootstrap,
                    "--partitions",
                    "3",
                    "--replication-factor",
                    "1",
                ],
                verbose=a.verbose,
            )
            if r.returncode == 0:
                ok += 1
        print(f"{ok}/{len(TOPICS)} topics OK")
        if ok != len(TOPICS):
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
