#!/usr/bin/env python3
"""Read-only per-currency reconciliation for FluxPay M2 ledger journals."""

import argparse
import os
import sys
from decimal import Decimal

from platform_commands import project_path


IMBALANCE_QUERY = """
SELECT journal_reference, currency,
       SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END) AS debits,
       SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END) AS credits
FROM ledger_entries
WHERE journal_reference IS NOT NULL
GROUP BY journal_reference, currency
HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END) <> 0
ORDER BY journal_reference, currency
"""

UNGROUPED_QUERY = """
SELECT currency, entry_type, COUNT(*) AS entry_count, SUM(amount) AS total_amount
FROM ledger_entries
WHERE journal_reference IS NULL
GROUP BY currency, entry_type
ORDER BY currency, entry_type
"""


def load_env(path):
    path = project_path(path)
    if not path.exists():
        return
    with path.open(encoding="utf-8") as env_file:
        for line in env_file:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                os.environ.setdefault(key, value)


def oracle_dsn(jdbc_url):
    prefix = "jdbc:oracle:thin:@"
    if not jdbc_url or not jdbc_url.startswith(prefix):
        raise ValueError("ORACLE_JDBC_URL must be an Oracle thin JDBC URL")
    dsn = jdbc_url[len(prefix) :]
    if dsn.startswith("//"):
        dsn = dsn[2:]
    if not dsn:
        raise ValueError("ORACLE_JDBC_URL has no database address")
    return dsn


def money(value):
    return Decimal(str(value)).quantize(Decimal("0.0000"))


def check(connection):
    with connection.cursor() as cursor:
        cursor.execute("SET TRANSACTION READ ONLY")
        cursor.execute(IMBALANCE_QUERY)
        imbalances = cursor.fetchall()
        cursor.execute(UNGROUPED_QUERY)
        ungrouped = cursor.fetchall()

    if imbalances:
        for journal, currency, debits, credits in imbalances:
            print(
                f"UNBALANCED journal={journal} currency={currency} "
                f"debits={money(debits)} credits={money(credits)}"
            )
    else:
        print("grouped journals balanced")

    if ungrouped:
        for currency, entry_type, count, amount in ungrouped:
            print(
                f"UNGROUPED currency={currency} type={entry_type} "
                f"count={count} amount={money(amount)}"
            )
    else:
        print("ungrouped entries=0")
    return bool(imbalances)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", default=".env")
    args = parser.parse_args()
    load_env(args.env_file)

    username = os.environ.get("ORACLE_USERNAME", "").strip()
    password = os.environ.get("ORACLE_PASSWORD", "")
    jdbc_url = os.environ.get("ORACLE_JDBC_URL", "").strip()
    if not username or not password or not jdbc_url:
        print("ledger check failed: ORACLE_JDBC_URL, ORACLE_USERNAME and ORACLE_PASSWORD are required")
        return 2

    try:
        import oracledb

        with oracledb.connect(user=username, password=password, dsn=oracle_dsn(jdbc_url)) as connection:
            return 1 if check(connection) else 0
    except ImportError:
        print("ledger check failed: install the Python 'oracledb' package")
        return 2
    except Exception as exception:
        print(f"ledger check failed: {type(exception).__name__}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
