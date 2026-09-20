"""Read-only migration and ledger readiness check. Never prints credentials."""
import os
import sys
import oracledb
from platform_commands import load_env

load_env('.env')
dsn = os.environ['ORACLE_JDBC_URL'].removeprefix('jdbc:oracle:thin:@').removeprefix('//')
try:
    with oracledb.connect(user=os.environ['ORACLE_USERNAME'], password=os.environ['ORACLE_PASSWORD'], dsn=dsn) as connection:
        with connection.cursor() as cursor:
            cursor.execute('SET TRANSACTION READ ONLY')
            print('Schema:', os.environ['ORACLE_USERNAME'])
            cursor.execute('SELECT "version", "script", "success" FROM "flyway_schema_history" ORDER BY "installed_rank"')
            for row in cursor:
                print('Migration:', *row)
            cursor.execute('SELECT COUNT(*) FROM users')
            print('Users:', cursor.fetchone()[0])
            cursor.execute('SELECT COUNT(*) FROM ledger_entries')
            print('Ledger entries:', cursor.fetchone()[0])
            cursor.execute("SELECT currency, account_role, COUNT(*) FROM wallets WHERE account_role <> 'CUSTOMER' GROUP BY currency, account_role ORDER BY currency, account_role")
            for row in cursor:
                print('System wallets:', *row)
except oracledb.Error as error:
    print('Database check failed:', error.args[0].code)
    sys.exit(1)
