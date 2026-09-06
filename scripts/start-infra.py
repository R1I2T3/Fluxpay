#!/usr/bin/env python3
"""Up Oracle+Kafka via compose, probe readiness, create 7 topics idempotently."""

import argparse, os, socket, subprocess, sys, time

TOPICS = [
    "payment.initiated",
    "payment.route.selected",
    "payment.screening.completed",
    "payout.submitted",
    "payout.failed",
    "payout.completed",
    "payment.refunded",
]


def load_env(path):
    if os.path.exists(path):
        for line in open(path):
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                os.environ.setdefault(k, v)


def run(cmd, verbose=False):
    if verbose:
        print("+", " ".join(cmd))
    return subprocess.run(cmd, capture_output=not verbose)


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
        if not wait_port("localhost", 9092, timeout=60, verbose=a.verbose):
            print("kafka port timeout")
            return 2
        ok = 0
        for t in TOPICS:
            r = run(
                [
                    "docker",
                    "exec",
                    "fluxpay-kafka",
                    "/opt/kafka/bin/kafka-topics.sh",
                    "--create",
                    "--if-not-exists",
                    "--topic",
                    t,
                    "--bootstrap-server",
                    "localhost:29092",
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
