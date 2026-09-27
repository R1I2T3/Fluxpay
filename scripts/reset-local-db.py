#!/usr/bin/env python3
"""Safely recreate one explicitly authorized local FluxPay Oracle schema."""

import argparse
import os
import subprocess
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit

from platform_commands import load_env

AUTHORIZED_SCHEMAS = frozenset(("FLUXPAY", "FLUXPAY_TEST"))
FLUXPAY_TOPICS = (
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
)
GRANTS = (
    "CREATE SESSION",
    "CREATE TABLE",
    "CREATE SEQUENCE",
    "CREATE TRIGGER",
    "CREATE PROCEDURE",
    "CREATE VIEW",
)


def validate_target(schema):
    normalized = (schema or "").strip().upper()
    if normalized not in AUTHORIZED_SCHEMAS:
        raise ValueError("target is not an authorized FluxPay schema")
    return normalized


def local_dsn(jdbc_url):
    prefix = "jdbc:oracle:thin:@"
    if not jdbc_url.startswith(prefix):
        raise ValueError("ORACLE_ADMIN_JDBC_URL must be an Oracle thin JDBC URL")
    address = jdbc_url[len(prefix) :]
    if not address.startswith("//"):
        raise ValueError("local Oracle URL must use //host:port/service syntax")
    parsed = urlsplit("oracle:" + address)
    if parsed.hostname not in ("localhost", "127.0.0.1", "::1"):
        raise ValueError("schema reset is restricted to local Oracle")
    if not parsed.path or parsed.path == "/":
        raise ValueError("local Oracle URL must name a service")
    return address[2:]


def connect_admin(jdbc_url, username, password):
    try:
        import oracledb
    except ImportError as exception:
        raise RuntimeError("install the Python 'oracledb' package to reset local Oracle") from exception
    return oracledb.connect(user=username, password=password, dsn=local_dsn(jdbc_url))


def inspect_target(connection, schema):
    target = validate_target(schema)
    with connection.cursor() as cursor:
        cursor.execute(
            """SELECT SYS_CONTEXT('USERENV','CURRENT_USER'),
                      SYS_CONTEXT('USERENV','DB_NAME'),
                      SYS_CONTEXT('USERENV','SERVICE_NAME'),
                      SYS_CONTEXT('USERENV','SERVER_HOST')
                 FROM dual"""
        )
        admin_user, database_name, service_name, server_host = cursor.fetchone()
        if str(admin_user).upper() == "SYS":
            raise ValueError("SYS connections are not accepted; use a scoped administrative account")
        cursor.execute("SELECT COUNT(*) FROM all_users WHERE username = :schema", schema=target)
        exists = cursor.fetchone()[0] == 1
    return {
        "adminUser": str(admin_user).upper(),
        "database": str(database_name),
        "service": str(service_name),
        "server": str(server_host),
        "schema": target,
        "exists": exists,
    }


def reset_schema(connection, schema, schema_password):
    target = validate_target(schema)
    if not schema_password:
        raise ValueError("target schema password is required")
    metadata = inspect_target(connection, target)
    with connection.cursor() as cursor:
        if metadata["exists"]:
            cursor.execute(f"DROP USER {target} CASCADE")
        cursor.execute(
            f"""BEGIN
                   EXECUTE IMMEDIATE 'CREATE USER {target} IDENTIFIED BY "' ||
                     REPLACE(:schema_password, '"', '""') || '"';
                 END;""",
            schema_password=schema_password,
        )
        for privilege in GRANTS:
            cursor.execute(f"GRANT {privilege} TO {target}")
        cursor.execute(f"ALTER USER {target} QUOTA UNLIMITED ON USERS")
    connection.commit()
    return metadata


def require_backend_stopped(base_url):
    try:
        urllib.request.urlopen(base_url.rstrip("/") + "/v3/api-docs", timeout=2).close()
    except (OSError, urllib.error.URLError):
        return
    raise RuntimeError("backend is still running; stop backend and outbox relay before reset")


def _compose_topics_command():
    return [
        "docker",
        "compose",
        "exec",
        "-T",
        "kafka",
        "/opt/kafka/bin/kafka-topics.sh",
        "--bootstrap-server",
        os.environ.get("KAFKA_CONTAINER_BOOTSTRAP_SERVERS", "kafka:9092"),
    ]


def inspect_compose_topics():
    result = subprocess.run(
        [*_compose_topics_command(), "--list"],
        capture_output=True,
        text=True,
        timeout=20,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("could not inspect the Compose Kafka topic inventory")
    existing = {line.strip() for line in result.stdout.splitlines() if line.strip()}
    return [topic for topic in FLUXPAY_TOPICS if topic in existing]


def reset_compose_topics(existing=None):
    owned = inspect_compose_topics() if existing is None else list(existing)
    base = _compose_topics_command()
    for topic in owned:
        result = subprocess.run(
            [*base, "--delete", "--topic", topic], capture_output=True, text=True, timeout=20, check=False
        )
        if result.returncode != 0:
            raise RuntimeError(f"could not delete FluxPay topic {topic}")
    for topic in FLUXPAY_TOPICS:
        result = subprocess.run(
            [
                *base,
                "--create",
                "--if-not-exists",
                "--topic",
                topic,
                "--partitions",
                "3",
                "--replication-factor",
                "1",
            ],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        if result.returncode != 0:
            raise RuntimeError(f"could not recreate FluxPay topic {topic}")
    return owned


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--schema", required=True)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--env-file", default=".env")
    args = parser.parse_args()
    load_env(args.env_file)

    try:
        target = validate_target(args.schema)
        jdbc_url = os.environ.get("ORACLE_ADMIN_JDBC_URL", "").strip()
        admin_user = os.environ.get("ORACLE_ADMIN_USERNAME", "").strip()
        admin_password = os.environ.get("ORACLE_ADMIN_PASSWORD", "")
        schema_password_key = "ORACLE_TEST_PASSWORD" if target == "FLUXPAY_TEST" else "ORACLE_PASSWORD"
        schema_password = os.environ.get(schema_password_key, "")
        infrastructure_mode = os.environ.get("FLUXPAY_INFRA_MODE", "compose").lower()
        if not jdbc_url or not admin_user or not admin_password or not schema_password:
            raise ValueError(
                "ORACLE_ADMIN_JDBC_URL, ORACLE_ADMIN_USERNAME, ORACLE_ADMIN_PASSWORD and "
                f"{schema_password_key} are required"
            )
        # Reject remote targets before importing a driver or opening a connection.
        local_dsn(jdbc_url)
        if infrastructure_mode not in ("compose", "external"):
            raise ValueError("FLUXPAY_INFRA_MODE must be compose or external")
        if (
            args.execute
            and infrastructure_mode == "external"
            and os.environ.get("FLUXPAY_EXTERNAL_BROKER_FRESH", "").lower() != "true"
        ):
            raise ValueError(
                "external mode requires a fresh isolated broker; set "
                "FLUXPAY_EXTERNAL_BROKER_FRESH=true after verifying it"
            )
        if args.execute:
            require_backend_stopped(os.environ.get("SEED_BASE_URL", "http://localhost:8080"))
            existing_topics = inspect_compose_topics() if infrastructure_mode == "compose" else []
        with connect_admin(jdbc_url, admin_user, admin_password) as connection:
            metadata = inspect_target(connection, target)
            action = "EXECUTE" if args.execute else "DRY RUN"
            print(
                f"{action}: schema={target} database={metadata['database']} "
                f"service={metadata['service']} server={metadata['server']} exists={metadata['exists']}"
            )
            if args.execute:
                reset_schema(connection, target, schema_password)
                removed_topics = reset_compose_topics(existing_topics) if infrastructure_mode == "compose" else []
                print(f"RESET COMPLETE: schema={target}; no export was created")
                if infrastructure_mode == "compose":
                    print(
                        "Kafka reset: removed="
                        + (",".join(removed_topics) if removed_topics else "none")
                        + "; recreated="
                        + ",".join(FLUXPAY_TOPICS)
                    )
                else:
                    print("Kafka reset: external broker was verified as fresh; no topics removed")
            else:
                print("No database changes made. Re-run with --execute after reviewing this target.")
        return 0
    except (OSError, RuntimeError, ValueError) as exception:
        print(f"reset refused: {exception}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
