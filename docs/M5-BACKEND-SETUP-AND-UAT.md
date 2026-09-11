# Member 5 backend: setup and end-to-end testing

This guide targets the revised `05-member5-compliance-ai.md` specification, **backend only**. The approved exception is Ollama `nomic-embed-text` with **768-dimensional M5 policy vectors**. The unrelated `document_embeddings` table remains 1536-dimensional.

The earlier drop-in READMEs describe the retired prototype. Do not copy their `permitAll` configuration, optional caller-supplied risk hints, old assessment bodies, `OPEN`/`CLOSED` case states, or edit/replay V602. The new API requires signed JWTs and authoritative assessment snapshots. Policies are immutable: create, read, and index are supported; arbitrary manual chunk insertion and policy update/delete are not required endpoints.

## 1. What “schema” means and why you need a test one

In Oracle, a schema is the collection of tables owned by a database user. Your existing `FLUXPAY` user owns your current application tables. **Do not delete or reset it.** Create a different user, for example `FLUXPAY_M5_TEST`, inside the existing `FREEPDB1` service. You do not need another database installation.

In SQL Developer, open an administrator connection to:

- Host: `localhost`
- Port: `1521`
- Connection type: Basic
- **Service name: `FREEPDB1`**, not SID and not the root service `FREE`
- Username: your existing administrator account, usually `SYSTEM`
- Password: the administrator password you set locally

Run this read-only check first:

```sql
SELECT SYS_CONTEXT('USERENV', 'CON_NAME') AS container_name,
       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS schema_name
FROM dual;
```

Continue only when the container is `FREEPDB1`. If the example user already exists, do not drop it: confirm that it is a disposable M5 test user or choose another unused name.

Run the following **manually as the administrator**, replacing the password placeholder locally. These commands create a new user; the M5 scripts never create users or grant privileges.

```sql
CREATE USER FLUXPAY_M5_TEST IDENTIFIED BY "REPLACE_WITH_YOUR_PRIVATE_PASSWORD"
  DEFAULT TABLESPACE USERS
  TEMPORARY TABLESPACE TEMP
  QUOTA 250M ON USERS;

GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER
  TO FLUXPAY_M5_TEST;
```

Use a private password; do not paste it in chat or commit it. If your database uses different tablespace names, ask its administrator to supply the correct names. Oracle requires a login privilege and a tablespace quota for a user to create its own objects. [Oracle CREATE USER reference](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/CREATE-USER.html).

Create another SQL Developer connection, named `M5 isolated test`, with username `FLUXPAY_M5_TEST`, its new password, and service `FREEPDB1`. Test and connect. In **that connection**, run:

```sql
SELECT USER AS session_user,
       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS schema_name,
       SYS_CONTEXT('USERENV', 'CON_NAME') AS container_name
FROM dual;

SELECT product, version_full FROM product_component_version;

SELECT VECTOR_DIMENSION_COUNT(TO_VECTOR('[1,0,0]', 3, FLOAT32)) AS dimensions
FROM dual;
```

Expected: both user/schema are `FLUXPAY_M5_TEST`, container is `FREEPDB1`, and the vector query returns `3`. This tests SQL vector support without creating any table. [Oracle vector dimension function](https://docs.oracle.com/en/database/oracle/oracle-database/26/sqlrf/vector_dimension_count.html).

## 2. Prepare a private environment file

Keep both environment and token files **outside the Git repository**. For example, create a local folder `C:\Users\Nitya\Documents\FluxpayPrivate` and save a copy of `docs/m5-solo.env.example` there as `m5.local.env`. Fill in its password and JWT secret locally. Use a fresh random JWT secret of at least 32 UTF-8 bytes. Do not reuse a production key.

### Notepad walkthrough (no prior setup knowledge needed)

1. Open File Explorer, go to `C:\Users\Nitya\Documents`, and create a folder named `FluxpayPrivate`.
2. Open `C:\Users\Nitya\Desktop\Fluxpay\docs\m5-solo.env.example` in Notepad. Choose **File → Save As**. Select the new private folder. Enter filename `m5.local.env`, select **All files** as the file type, and select **UTF-8**. Ensure Windows has not named it `m5.local.env.txt`.
3. Replace `ORACLE_PASSWORD=REPLACE_LOCALLY` with `ORACLE_PASSWORD=` followed by the password you chose for **FLUXPAY_M5_TEST** in section 1. This is not the `SYSTEM` administrator password or your old `FLUXPAY` password. Editing this file does not change a database password; it tells Java which existing password to use.
4. Open PowerShell and run the command below. It prints a new random secret locally. Copy the resulting text; do not send it in chat.

   ```powershell
   python -c "import secrets; print(secrets.token_urlsafe(48))"
   ```

5. In Notepad, replace everything after `JWT_SECRET=` with that generated text. Oracle does not issue this secret. It is a separate signing key used by the test application to create and verify its one-hour Bearer tokens. Keep it private.
6. Leave `ORACLE_USERNAME` and `M5_TEST_SCHEMA` as `FLUXPAY_M5_TEST` if you created that exact user. Leave the URL, permission flag, and mock mode as shown. Keep one setting per line, with no spaces around `=`.
7. Leave `M5_SOLO_TOKEN_FILE=C:/Users/Nitya/Documents/FluxpayPrivate/m5.tokens.json`. Do not create this JSON file yourself; the launcher writes it after successful setup.
8. Save the file. Tell the assistant only the full path and that the test user/file are ready, not their secret contents. The assistant can then run the isolated tests without touching your existing application schema.

If the test-user password contains leading/trailing spaces or quote characters, choose a simpler strong random password for this disposable user when creating it. Do not silently change a password in the env file and expect Oracle to change with it. There are no real passwords in the supplied template.

The important distinction is:

```dotenv
ORACLE_USERNAME=FLUXPAY_M5_TEST
M5_TEST_SCHEMA=FLUXPAY_M5_TEST
M5_ALLOW_FIXTURE_SETUP=true
```

The wrapper requires an explicit test declaration; the Java preflight also compares it with the actual current schema **before Flyway and fixture writes**. The private file overrides stale inherited environment values. The ordinary application `.env` is not silently loaded.

Start with `EMBEDDING_MODE=mock`. This requires Oracle for persistence but does not require Ollama, Kafka, FX, Docker, or another member's services. Section 8 explains switching to Ollama.

## 3. Run database-independent tests first

Open PowerShell in `C:\Users\Nitya\Desktop\Fluxpay`:

```powershell
python scripts/test_m5.py --suite unit
python -m unittest discover -s tests -p 'test_m5*.py'
```

The Java selection is `M5*UnitTest`, `M5*WebTest`, and `M5*ContractTest`. The wrapper requires actually executed tests in every selected group and propagates failures. It never starts the waiting solo launcher and never treats missing/skipped Oracle tests as success.

## 4. Start the isolated backend and apply migrations

```powershell
python scripts/start_m5_solo.py --env-file 'C:\Users\Nitya\Documents\FluxpayPrivate\m5.local.env'
```

Keep this terminal running. The test-only application applies the full Flyway chain, adds collision-checked synthetic fixture rows, writes one-hour signed tokens to the private path in `M5_SOLO_TOKEN_FILE`, and listens on loopback port 8081 by default. `Ctrl+C` stops this backend; it does not drop tables.

Do **not** use `scripts/start-backend.py` for solo testing. That script starts the integrated application, which intentionally requires real payment-reader and review-delivery adapters plus explicit business-rule settings. Fixture implementations are not packaged into the production backend.

The new compliance source of truth is `screening_cases`, with `m5_screening_heads` and `m5_review_decisions`. The old `compliance_cases` demo rows remain historical/inactive and will not appear as new assessment cycles. A new assessment is created through the API, not by manually inserting a case.

Check applied versions in the isolated SQL Developer connection:

```sql
SELECT "version", "description", "success"
FROM "flyway_schema_history"
ORDER BY "installed_rank";
```

Migration versions V605–V607 are locally reserved by this change; coordinate them with other branches before a shared deployment. The V607 normalized policy-hash backfill is a Java Flyway migration supplied by M5 configuration, so use the M5 application/test launcher, not a SQL-only Flyway CLI invocation.

**Shared-schema upgrade caution:** V605 stops before its own DDL if `screening_cases` has unclassified legacy history or `compliance_cases` has non-seed/changed history. Do not bypass it with `repair`, delete old cases, or guess how `CLOSED` maps. Reconcile actual history with evidence in a separate migration plan. Oracle DDL can commit partially; a failed later migration requires inspection before retry, not automatic cleanup.

## 5. Test data and authentication

The fixture application supplies synthetic users, BCrypt hashes, KYC rows, wallets, recipients, and payments satisfying the baseline foreign keys. Reserved identities are checked on rerun; matching data is reused and collisions stop setup. Existing assessments and decisions are never reset. Risk scenarios change only the test reader's observations; M5 does not move money or mutate payment lifecycle state.

Open the private token JSON locally. Postman uses its `adminToken`, `userToken`, and `foreignUserToken` values. Set Authorization type **Bearer Token**; do not include the word `Bearer` twice. Tokens expire after one hour; restart the launcher to issue fresh tokens without resetting data. Never export populated secrets into a committed collection.

Base URL: `http://127.0.0.1:8081`. Success is wrapped in `{"correlationId":"...","data":...}`. Error responses contain `correlationId`, `code`, `message`, `fieldErrors`, and `ts`. Swagger's “Example Value” is documentation, not the live response; inspect the actual status and response body.
