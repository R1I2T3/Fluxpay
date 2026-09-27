#!/usr/bin/env python3
"""Start or probe FluxPay infrastructure and provision its Kafka topics."""

import argparse
import os
import shutil
import socket
import subprocess
import sys
import time
from urllib.parse import urlsplit

from platform_commands import PROJECT_ROOT, load_env

TOPICS = [
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
]

DEFAULT_BOOTSTRAP = "localhost:9092"
DEFAULT_ORACLE = "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"


def run(cmd, verbose=False):
    if verbose:
        print("+", " ".join(cmd))
    return subprocess.run(cmd, cwd=PROJECT_ROOT, capture_output=not verbose, check=False)


def split_host_port(value, default_port):
    """Split a host:port bootstrap value without third-party parsing."""
    host, sep, port = value.rpartition(":")
    if not sep or not host or not port.isdigit():
        return "localhost", default_port
    return host.strip("[]"), int(port)


def oracle_host_port(jdbc_url):
    prefix = "jdbc:oracle:thin:@"
    if not jdbc_url.startswith(prefix):
        raise ValueError("ORACLE_JDBC_URL must be an Oracle thin JDBC URL")
    address = jdbc_url[len(prefix) :]
    if not address.startswith("//"):
        raise ValueError("ORACLE_JDBC_URL must use //host:port/service syntax")
    parsed = urlsplit("oracle:" + address)
    if not parsed.hostname:
        raise ValueError("ORACLE_JDBC_URL has no host")
    return parsed.hostname, parsed.port or 1521


def kafka_script(name):
    """Locate a Kafka CLI script for externally managed Kafka."""
    kafka_home = os.environ.get("KAFKA_HOME", "")
    suffix = ".bat" if sys.platform == "win32" else ".sh"
    candidates = []
    if kafka_home:
        candidates.append(os.path.join(kafka_home, "bin", name + suffix))
        if sys.platform == "win32":
            candidates.append(os.path.join(kafka_home, "bin", "windows", name + ".bat"))
    found = shutil.which(name + suffix) or shutil.which(name)
    if found:
        candidates.append(found)
    return next((candidate for candidate in candidates if os.path.exists(candidate)), None)


def wait_port(host, port, timeout=30, verbose=False):
    started = time.time()
    while time.time() - started < timeout:
        try:
            socket.create_connection((host, port), timeout=2).close()
            return True
        except OSError:
            if verbose:
                print(f"waiting {host}:{port}...")
            time.sleep(2)
    return False


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=["compose", "external"])
    parser.add_argument("--skip-oracle", action="store_true")
    parser.add_argument("--skip-kafka", action="store_true")
    parser.add_argument("--skip-topics", action="store_true")
    parser.add_argument("--env-file", default=".env")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    load_env(args.env_file)

    mode = args.mode or os.environ.get("FLUXPAY_INFRA_MODE", "compose").lower()
    if mode not in ("compose", "external"):
        print("invalid FLUXPAY_INFRA_MODE; expected compose or external")
        return 2

    if mode == "compose":
        services = []
        if not args.skip_oracle:
            services.append("oracle")
        if not args.skip_kafka:
            services.append("kafka")
        if services:
            result = run(["docker", "compose", "up", "-d", *services], verbose=args.verbose)
            if result.returncode != 0:
                print("compose up failed")
                return 1

    if not args.skip_oracle:
        try:
            oracle_host, oracle_port = oracle_host_port(os.environ.get("ORACLE_JDBC_URL", DEFAULT_ORACLE))
        except ValueError as exception:
            print(f"oracle configuration failed: {exception}")
            return 2
        if not wait_port(oracle_host, oracle_port, timeout=120, verbose=args.verbose):
            print("oracle port timeout")
            return 2
        print("oracle port OK")

    if args.skip_kafka:
        return 0

    bootstrap = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", DEFAULT_BOOTSTRAP)
    kafka_host, kafka_port = split_host_port(bootstrap, 9092)
    if not wait_port(kafka_host, kafka_port, timeout=60, verbose=args.verbose):
        print("kafka port timeout")
        return 2
    if args.skip_topics:
        print("kafka port OK; topic provisioning skipped")
        return 0

    if mode == "compose":
        base_command = [
            "docker",
            "compose",
            "exec",
            "-T",
            "kafka",
            "/opt/kafka/bin/kafka-topics.sh",
            "--bootstrap-server",
            os.environ.get("KAFKA_CONTAINER_BOOTSTRAP_SERVERS", "kafka:9092"),
        ]
    else:
        topics_script = kafka_script("kafka-topics")
        if topics_script is None:
            print("external Kafka topic provisioning needs kafka-topics; set KAFKA_HOME or use --skip-topics")
            return 2
        base_command = [topics_script, "--bootstrap-server", bootstrap]

    if run([*base_command, "--list"], verbose=args.verbose).returncode != 0:
        print("kafka bootstrap probe failed")
        return 2

    created = 0
    for topic in TOPICS:
        result = run(
            [
                *base_command,
                "--create",
                "--if-not-exists",
                "--topic",
                topic,
                "--partitions",
                "3",
                "--replication-factor",
                "1",
            ],
            verbose=args.verbose,
        )
        if result.returncode == 0:
            created += 1
    print(f"{created}/{len(TOPICS)} topics OK")
    return 0 if created == len(TOPICS) else 1


if __name__ == "__main__":
    sys.exit(main())
