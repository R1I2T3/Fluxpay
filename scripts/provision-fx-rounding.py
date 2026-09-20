"""Add only missing zero-balance FX_GAIN_LOSS system wallets after V008.

Scoped to the inspected local schema and configured system user; no existing
balances, user identities, roles, or ledger entries are changed.
"""
import argparse
import os
import uuid
import oracledb
from platform_commands import load_env

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--apply', action='store_true')
args=parser.parse_args()
load_env('.env')
if os.environ['ORACLE_USERNAME'].upper() != 'FLUXPAY':
    raise SystemExit('Expected the inspected local FLUXPAY schema.')
owner=uuid.UUID(os.environ['FLUXPAY_SYSTEM_USER_ID']).bytes
dsn=os.environ['ORACLE_JDBC_URL'].removeprefix('jdbc:oracle:thin:@').removeprefix('//')
with oracledb.connect(user=os.environ['ORACLE_USERNAME'],password=os.environ['ORACLE_PASSWORD'],dsn=dsn) as connection:
    with connection.cursor() as cursor:
        cursor.execute("SELECT currency, account_role FROM wallets WHERE user_id=:owner AND account_role<>'CUSTOMER'", owner=owner)
        existing=set(cursor.fetchall())
        required={(currency,role) for currency in ('USD','EUR','INR') for role in ('FX_CLEARING','DEMO_CLEARING','PAYOUT_CLEARING','FEE_REVENUE')}
        if not required.issubset(existing):
            raise SystemExit('Configured system user does not own the expected existing system wallets.')
        missing=[currency for currency in ('USD','EUR','INR') if (currency,'FX_GAIN_LOSS') not in existing]
        print('Missing FX rounding wallets:', ', '.join(missing) or 'none')
        if args.apply:
            for currency in missing:
                cursor.execute("INSERT INTO wallets (user_id,currency,account_role) SELECT :owner,:currency,'FX_GAIN_LOSS' FROM dual WHERE NOT EXISTS (SELECT 1 FROM wallets WHERE user_id=:owner AND currency=:currency AND account_role='FX_GAIN_LOSS')", owner=owner,currency=currency)
            connection.commit()
            print('Provisioned:', len(missing), 'zero-balance system wallets. Existing data unchanged.')
