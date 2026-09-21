"""Create a private logical schema snapshot before an approved local upgrade.

This is an export of table DDL, columns and rows, not an Oracle RMAN backup.
The output contains sensitive application data and stays in ignored logs/.
"""
import array
import base64
import datetime
import decimal
import gzip
import json
import os
from pathlib import Path
import oracledb
from platform_commands import load_env, PROJECT_ROOT

def encode(value):
    if isinstance(value, (list, tuple)):
        return [encode(item) for item in value]
    if isinstance(value, dict):
        return {key: encode(item) for key, item in value.items()}
    if isinstance(value, oracledb.LOB):
        return encode(value.read())
    if isinstance(value, bytes):
        return {'$bytes': base64.b64encode(value).decode('ascii')}
    if isinstance(value, (datetime.date, datetime.datetime)):
        return {'$date': value.isoformat()}
    if isinstance(value, decimal.Decimal):
        return {'$decimal': str(value)}
    if isinstance(value, array.array):
        return {'$array': value.tolist(), 'typecode': value.typecode}
    if value is None or isinstance(value, (str, int, float)):
        return value
    raise TypeError('Unsupported database value type: ' + type(value).__name__)

load_env('.env')
schema = os.environ['ORACLE_USERNAME']
if schema.upper() != 'FLUXPAY':
    raise SystemExit('This backup is scoped to the inspected local FLUXPAY schema.')
dsn = os.environ['ORACLE_JDBC_URL'].removeprefix('jdbc:oracle:thin:@').removeprefix('//')
snapshot = {'schema': schema, 'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'tables': []}
with oracledb.connect(user=schema, password=os.environ['ORACLE_PASSWORD'], dsn=dsn) as connection:
    with connection.cursor() as cursor:
        cursor.execute('SET TRANSACTION READ ONLY')
        cursor.execute("SELECT table_name FROM user_tables WHERE nested='NO' AND secondary='N' ORDER BY table_name")
        names = [row[0] for row in cursor]
        for name in names:
            cursor.execute("SELECT DBMS_METADATA.GET_DDL('TABLE', :name) FROM dual", name=name)
            ddl = cursor.fetchone()[0].read()
            cursor.execute('SELECT column_name, data_type FROM user_tab_columns WHERE table_name=:name ORDER BY column_id', name=name)
            definitions = cursor.fetchall()
            expressions = []
            for column, datatype in definitions:
                quoted = '"' + column.replace('"', '""') + '"'
                if datatype.startswith('TIMESTAMP'):
                    pattern = 'YYYY-MM-DD"T"HH24:MI:SS.FF9' + (' TZR' if 'TIME ZONE' in datatype else '')
                    expressions.append("TO_CHAR(" + quoted + ", '" + pattern + "') AS " + quoted)
                else:
                    expressions.append(quoted)
            cursor.execute('SELECT ' + ','.join(expressions) + ' FROM "' + name.replace('"', '""') + '"')
            columns = [column[0] for column in cursor.description]
            rows = [[encode(value) for value in row] for row in cursor]
            snapshot['tables'].append({'name': name, 'ddl': ddl, 'columns': columns, 'types': definitions, 'rows': rows})
output = PROJECT_ROOT / 'logs' / ('schema-before-main-' + datetime.datetime.now().strftime('%Y%m%d-%H%M%S') + '.json.gz')
output.parent.mkdir(exist_ok=True)
with gzip.open(output, 'xt', encoding='utf-8') as archive:
    json.dump(snapshot, archive)
with gzip.open(output, 'rt', encoding='utf-8') as archive:
    verified = json.load(archive)
assert len(verified['tables']) == len(snapshot['tables'])
print('Logical snapshot verified:', output)
print('Tables:', len(snapshot['tables']), 'Rows:', sum(len(table['rows']) for table in snapshot['tables']))
