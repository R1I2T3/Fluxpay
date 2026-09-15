# Member 5 — final step-by-step testing guide

This is the single testing guide to follow for Member 5: server-derived risk and reasons, compliance cases, Member 3 payment-review integration, policy indexing, and real Oracle vector search. It includes the recovery steps learned during the September 2026 testing sessions. It assumes Windows PowerShell, Oracle Free PDB `FREEPDB1`, synthetic local fixtures, and the backend on port `8082`. Commands run from `C:\Users\Nitya\Desktop\Fluxpay` unless stated otherwise.

This is a procedure and acceptance checklist, **not a claim that every test has already passed**. See [recorded evidence and final sign-off](#19-recorded-evidence-and-final-sign-off) for what was observed and what still needs fresh evidence. No frontend testing is required here.

For an explanation of **every individual saved request**, use [section 20: detailed endpoint reference](#20-detailed-endpoint-reference--all-47-saved-requests). It explains each folder's purpose, request inputs, backend work, expected response fields, state changes, and what a passing result actually proves. Sections 3–19 remain the execution order and recovery guide.

Use these two Postman files:

- [M5-Backend.postman_collection.json](postman/M5-Backend.postman_collection.json) — the canonical 47-request collection.
- [m5-current-policy-ids.json](postman/m5-current-policy-ids.json) — the 13 current policy ID/title pairs, usable as a manual checklist or Runner data.

The retired prototype and isolated-fixture collections have been removed. Use only the canonical collection above for the current integrated API.

## Start here: order and stopping points

| Order | What to do | Why it is necessary | How to run |
| --- | --- | --- | --- |
| Setup | [Database and services](#3-one-time-prerequisites), [JWT](#4-create-signed-tokens), [Postman variables](#5-import-configure-and-reset-postman-safely) | Establish valid upstream data and consistent private configuration | Once; refresh tokens when needed, not the database |
| 00 | [Authorization](#6-folder-00--authorization) | Prove who can read or administer the system before testing mutations | Four requests |
| 01 | [Provider and corpus readiness](#71-folder-01--provider-and-policy-readiness) | Distinguish provider/configuration problems from search problems | Three requests; repeat reconciliation once |
| 02 | [Index 13 policies](#72-folder-02--index-all-13-current-policies-without-runner-data) | Make current policy text searchable using real stored vectors | Two requests per policy; manual option below needs no data-file feature |
| 03 | [Grounding and no-answer](#73-folder-03--prove-grounding-and-the-no-answer-path) | Prove relevant retrieval and refusal to invent unrelated answers | Two requests |
| 04 | [Direct R1 assessment](#8-folder-04--direct-authoritative-risk-and-replay) | Prove server-owned risk and safe evidence-only cases | Six requests; separate SQL fixture |
| 05 | [LOW and MEDIUM risk](#9-folder-05--isolated-low-and-medium-risk-scenarios) | Isolate R2, then show R2/R3/R5 aggregation | Eight requests, one fresh recipient, exact order |
| A | [HIGH risk and approval](#10-folder-a--high-risk-review-approval-and-safe-reconfirmation) | Prove a held payment resumes only after a delivered decision and fresh confirmation | Eleven requests; pause for delivery |
| B | [Rejection](#11-folder-b--separate-rejection-path) | Prove a different review can stop a payment without posting | New A1–A4 setup, then three B requests |
| C | [Operator reads and contextual search](#12-folder-c--read-only-operator-checks-and-copilot-context) | Prove usable case/policy inspection and correct context boundaries | Five requests |
| D | [Deliberate policy mutations](#13-folder-d--deliberate-policy-publication-and-indexing) | Prove publication, deduplication, indexing, and replay | Three requests; read the persistent-corpus warning first |
| Finish | [SQL evidence](#16-read-only-oracle-evidence), [regressions](#18-automated-regression-checks), [sign-off](#19-recorded-evidence-and-final-sign-off) | Check persistence and distinguish passing responses from complete acceptance | Record evidence; do not infer a pass |

For the least confusing workflow, open each request and click **Send** in sequence. Do not run the entire collection in Runner. A and B require asynchronous waits; D deliberately changes the corpus. Folder 02 is the only place a 13-row data file belongs. The collection has **47 saved request templates**, not 47 total sends: policy repetitions and delivery polling add sends.

If you already completed setup and fixtures, **do not reset the database**. Start with fresh tokens and folder 00. If resuming a failed payment, first use the recovery section; do not clear all its IDs and start clicking from A1 again.

## 1. What this test proves

A successful complete run demonstrates the following boundaries:

1. JWT signatures and ADMIN/USER authorization are enforced.
2. Risk and reason codes are computed from stored payment, KYC, recipient snapshot, and history data. A caller cannot submit or override them.
3. The integrated payment confirmation path creates a real M5 review hold before any posting.
4. An ADMIN decision is stored durably and delivered asynchronously to M3.
5. Approval requires a fresh quote and fresh idempotency key before the payment reaches `PROCESSING`.
6. Rejection ends in `REJECTED` without a payment debit.
7. Ollama returns real 768-dimensional embeddings.
8. The 13 current policy revisions are indexed into Oracle `VECTOR(768,FLOAT32)` generations with `mock=false`.
9. A relevant Copilot question returns cited current-policy excerpts, while an unrelated question returns the explicit no-answer response.

It does **not** prove Kafka delivery, an M4 payout, bank settlement, refund execution, live sanctions/PEP screening, or legal/regulatory compliance. The current integration ends at `PROCESSING` plus durable outbox state.

The responsibilities being exercised are:

| Owner | Authoritative input or action | What Member 5 does with it |
| --- | --- | --- |
| Member 1 | User identity and stored KYC observations | Reads verified/unverified status; does not let the test caller invent it |
| Member 2 | Wallet ownership, available funds, balanced ledger | M3 checks/posts through the wallet/ledger boundary; M5 does not write arbitrary balances |
| Member 3 | Persisted payment, recipient snapshot, quotes, confirmation, review binding | M5 assesses those facts and delivers a durable decision to the matching held payment |
| Member 5 | Deterministic rules, assessment evidence, case decisions, policy generations and retrieval | Produces risk/reasons and grounded policy references without confusing search with authorization |

The vector database does **not** calculate or override payment risk. Policy retrieval is an operator-reference feature. The risk engine separately evaluates authoritative stored facts using the six configured rules.

## 2. Why no new backend test endpoint was added

The current application API already contains the endpoints needed for these tests:

| Area | Current endpoint |
| --- | --- |
| Read server-owned assessment inputs | `GET /api/compliance/payments/{paymentId}/assessment-context` |
| Create/replay an assessment | `POST /api/compliance/assess/{paymentId}` |
| List and inspect cases | `GET /api/compliance/cases`, `GET /api/compliance/cases/{caseId}` |
| Read a payment passport | `GET /api/compliance/payments/{paymentId}/passport` |
| Approve/reject a bound review | `PUT /api/compliance/cases/{caseId}/approve`, `PUT /api/compliance/cases/{caseId}/reject` |
| Manage/index policies | `POST/GET /api/policies`, `GET /api/policies/{id}`, `POST /api/policies/{id}/index` |
| Reconcile policy hashes | `POST /api/policies/reconcile-hashes` |
| Grounded search | `POST /api/copilot/ask` |
| Integrated payment path | `/api/payments/draft`, `/{id}/quotes`, `/{id}/confirm`, `/{id}` |

Adding an endpoint that accepts `risk`, `riskReasons`, KYC state, a fake review activation, or fabricated vector chunks would bypass the feature being tested. It would also duplicate Member 1 or Member 3 authority. Therefore the collection calls the existing real endpoints and uses clearly separated SQL only to create upstream test observations that the API cannot legitimately create.

## 3. One-time prerequisites

Have these ready before running commands:

- Java **17** installed; use your actual JDK directory for `JAVA_HOME`. Maven is supplied by the repository wrapper `mvnw.cmd`.
- Postman, Ollama, and SQL*Plus available. A configured SQL client can run the SQL files instead of SQL*Plus.
- A running vector-capable Oracle installation, open `FREEPDB1`, and an existing local `FLUXPAY` account with the project's required schema privileges. These instructions configure/use that account; they do not install Oracle or create an administrative database account.
- Network access for uncached Maven dependencies, the initial embedding-model download, and the external FX quote provider used by payment quoting. Once installed, Ollama embeddings run locally.
- The current checkout, including `docs/scripts`, `docs/sql`, and migrations through V708. Use synthetic local data only, never a production account or real payable recipient.

If a command such as `java`, `sqlplus`, or `ollama` is not recognized, resolve that installation/PATH prerequisite before continuing. Do not interpret a missing local executable as an M5 API failure.

### 3.1 Private and project configuration

The private file is the Git-ignored repository-root file:

```text
C:\Users\Nitya\Desktop\Fluxpay\.env
```

It must contain your actual local values and must never be committed or exported:

```dotenv
ORACLE_JDBC_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1
ORACLE_USERNAME=fluxpay
ORACLE_PASSWORD=your-local-password
JWT_SECRET=your-local-secret-with-at-least-32-utf8-bytes
```

The project-owned non-secret configuration is:

```text
backend/src/main/resources/m5-backend.properties
```

It contains placeholders and M5 settings, not the password or signing secret. The start script reads the four allowed private values from `.env`; the token helper reads only `JWT_SECRET` when it is not already inherited. Neither needs to print those secrets.

### 3.2 Apply migrations on a new database

Skip this subsection if your existing database already has the current migrations. The current fresh-schema M5 sequence is V701 through V708, alongside the other members' prerequisite migrations. Do not apply only the M5 files to an otherwise empty schema, rename already-applied migrations, or delete Flyway history to make validation pass. The former one-off pre-V701 reset script is historical and must not be rerun on the rebuilt schema. An incompatible history needs an exact-target reviewed migration/reset procedure, not a testing workaround.

From the repository root, load the four `.env` values into the current PowerShell process without executing the file:

```powershell
foreach ($m5Line in Get-Content -LiteralPath .env) {
  if ($m5Line -match '^\s*(ORACLE_JDBC_URL|ORACLE_USERNAME|ORACLE_PASSWORD|JWT_SECRET)\s*=\s*(.*)$') {
    $m5Name = $Matches[1]
    $m5Value = $Matches[2].Trim()
    if ($m5Value.Length -ge 2 -and (($m5Value.StartsWith('"') -and $m5Value.EndsWith('"')) -or ($m5Value.StartsWith("'") -and $m5Value.EndsWith("'")))) {
      $m5Value = $m5Value.Substring(1, $m5Value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($m5Name, $m5Value, 'Process')
  }
}
```

Inspect before migrating:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:FLYWAY_URL = $env:ORACLE_JDBC_URL
$env:FLYWAY_USER = $env:ORACLE_USERNAME
$env:FLYWAY_PASSWORD = $env:ORACLE_PASSWORD
$m5MigrationDir = (Resolve-Path .\backend\src\main\resources\db\migration).Path.Replace('\', '/')
.\mvnw.cmd -B -ntp -f backend/pom.xml "-Dflyway.locations=filesystem:$m5MigrationDir" '-Dflyway.schemas=FLUXPAY' '-Dflyway.defaultSchema=FLUXPAY' '-Dflyway.createSchemas=false' org.flywaydb:flyway-maven-plugin:10.22.0:info
```

If and only if the target and pending list are correct, apply the pending migrations in the same terminal:

```powershell
.\mvnw.cmd -B -ntp -f backend/pom.xml "-Dflyway.locations=filesystem:$m5MigrationDir" '-Dflyway.schemas=FLUXPAY' '-Dflyway.defaultSchema=FLUXPAY' '-Dflyway.createSchemas=false' org.flywaydb:flyway-maven-plugin:10.22.0:migrate
```

Repeat the `:info` command afterward. Success means V701–V708 and the prerequisite migrations are applied, with no failed history. If already applied, do not alter them or recreate tables for a new test run. Postman never runs migrations.

### 3.3 Insert the normal integrated-test fixture once

Open a new SQL*Plus connection and run:

```powershell
sqlplus -L fluxpay@localhost:1521/FREEPDB1 @docs/sql/m5-backend-test-data.sql
```

The password is prompted. The script inserts synthetic users, KYC cases, recipients, wallets, and a balanced USD 10,000 test-funding journal. It does not insert risk results or compliance decisions. Its existing-ID/email guard refuses to overwrite records. A guard failure may mean an earlier installation or a collision; inspect the expected records before assuming the fixture is complete. Do not rerun funding or reset tables to fix Postman variables.

The main Postman flow uses:

| Variable | Fixture value |
| --- | --- |
| `sourceWalletId` | `5f100000-0000-0000-0000-000000000001` |
| Safe India `safeRecipientId` | `5f200000-0000-0000-0000-000000000001` |
| Synthetic RU demo-risk `recipientId` | `5f200000-0000-0000-0000-000000000002` |
| Verified sender user ID | `5f000000-0000-0000-0000-000000000003` |
| ADMIN user ID | `5f000000-0000-0000-0000-000000000001` |

These are non-payable test records. The RU list is static demo configuration, not a current sanctions determination.

### 3.4 Separate R1 fixture for folder 04

M3 correctly prevents a pending-KYC user from creating a normal draft. To test M5 rule R1 without adding a bypass endpoint, separately run:

```powershell
sqlplus -L fluxpay@localhost:1521/FREEPDB1 @docs/sql/m5-risk-demo-fixture.sql
```

Copy the printed `paymentId` into the Postman environment variable `riskFixturePaymentId`. This fixture inserts a pending-KYC authoritative payment observation but no assessment or case. Folder 04 is optional for a quick smoke test, but include it when demonstrating all six risk rules.

If you see `ORA-20702: M5 fixture email already exists`, this attempt inserted no fixture rows. Do not delete the existing user. Retrieve the payment read-only, and verify it is the intended fixture:

```sql
SELECT LOWER(
  SUBSTR(RAWTOHEX(p.id),1,8) || '-' || SUBSTR(RAWTOHEX(p.id),9,4) || '-' ||
  SUBSTR(RAWTOHEX(p.id),13,4) || '-' || SUBSTR(RAWTOHEX(p.id),17,4) || '-' ||
  SUBSTR(RAWTOHEX(p.id),21,12)) AS payment_id
FROM payments p
JOIN wallets w ON w.id = p.sender_wallet_id
JOIN users u ON u.id = w.user_id
WHERE LOWER(u.email) = 'risk.sender.01@example.invalid';
```

### 3.5 Start Ollama and the backend

Ensure Ollama is running and the configured model exists:

```powershell
ollama pull nomic-embed-text
```

If `ollama serve` is not already running, start it in its own terminal. Then start the current integrated backend from a separate terminal:

```powershell
cd C:\Users\Nitya\Desktop\Fluxpay
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
& "$env:JAVA_HOME\bin\java.exe" -version
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\docs\scripts\Start-M5Backend.ps1
```

Use your installed Java 17 directory if it differs from the example, and check that the version output is 17. Set it in this terminal even if you already set it in a different migration terminal.

Wait for the application to report that it started on `http://127.0.0.1:8082`. Keep both Ollama and the backend running. Do not start a second process on the same port. The script enables `m5-risk`, `m5-backend`, and `m5-m3-integration`; the payment walkthrough needs the integration profile. It does not apply migrations or seed data. Keep review delivery enabled for A/B.

The current project configuration includes the Oracle UTC-session correction used to resolve immediately expired quotes. Restart an older backend once to load that correction and establish new connections. Existing incorrectly timestamped quotes are not repaired; obtain new quotes. Ordinary JWT expiry alone does not require a restart.

## 4. Create signed tokens

**Purpose:** separate authentication failure from risk failure. An unsigned, expired, or incorrectly signed request cannot reach the feature under test.

Authentication belongs to Member 1; there is no M5 token-minting API. For these local synthetic accounts only, the helper generates HS256 JWTs valid for **one hour**. It must use the same private `JWT_SECRET` as the running backend.

First-time setup: create/select the private `Fluxpay M5 Local` environment with blank `adminToken` and `userToken` rows as described in section 5, so you have somewhere to paste each token. Then, in a new PowerShell terminal, generate the ADMIN token and copy it without printing it:

```powershell
cd C:\Users\Nitya\Desktop\Fluxpay
Remove-Item Env:JWT_SECRET -ErrorAction SilentlyContinue
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\docs\scripts\New-M5TestToken.ps1 -Account admin | Set-Clipboard
```

Paste it immediately into the private Postman environment value `adminToken`. Then generate and paste the USER token into `userToken`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\docs\scripts\New-M5TestToken.ps1 -Account sender | Set-Clipboard
```

Removing the inherited process variable makes the helper read the project's private `.env`; it does not delete or change that file. This avoids the earlier situation where a stale shell secret produced tokens that disagreed with the backend.

Paste the raw three-part JWT only: no surrounding quotes, no literal `Bearer ` prefix, and no variable-name label. The collection adds the Bearer header. If Postman displays a Vault reference chip, update its underlying secret or replace the value with the fresh private JWT; the visible label is not the token itself.

Refresh ADMIN and USER independently after expiry. A valid USER token does not prove the ADMIN token is still valid. After pasting, rerun the corresponding signed request in folder 00. **Keep scenario IDs and idempotency keys unchanged during token refresh.** Never commit, screenshot, share, or export tokens or the signing secret.

## 5. Import, configure, and reset Postman safely

1. In Postman, choose **Import** and import `docs/postman/M5-Backend.postman_collection.json`.
2. Create a private environment named `Fluxpay M5 Local`.
3. Add the following environment variables. Put secrets in **Current value** only and leave shareable/initial values empty when your Postman version shows both columns.

| Variable | Current value |
| --- | --- |
| `baseUrl` | `http://127.0.0.1:8082` |
| `ollamaUrl` | `http://127.0.0.1:11434` |
| `embeddingModel` | `nomic-embed-text` |
| `expectedDimensions` | `768` |
| `adminToken` | raw JWT copied from the ADMIN helper invocation |
| `userToken` | raw JWT copied from the sender helper invocation |
| `allowPolicyIndexing` | `false` initially |
| `riskFixturePaymentId` | UUID printed by the R1 SQL fixture, when running 04 |
| `sourceWalletId` | `5f100000-0000-0000-0000-000000000001` |
| `safeRecipientId` | fresh India-recipient UUID for folder 05; see section 9 |
| `recipientId` | `5f200000-0000-0000-0000-000000000002` for A/B |
| `sourceAmount` | `1500.0000` for A/B |
| `currentPolicyId` | one policy UUID from section 7.2, for manual indexing |
| `expectedPolicyTitle` | matching exact title from section 7.2 |

4. Select `Fluxpay M5 Local` in Postman's environment selector.
5. Do not paste Oracle credentials or `JWT_SECRET` into Postman. The API never needs them.

### 5.1 How to send and inspect a request

Expand the collection and folder, select the named request, verify the active environment, and click **Send**. Leave the saved method, body, headers, and authorization in place unless a subsection explicitly instructs otherwise. For JSON bodies, use **Body → raw → JSON**, not query parameters or form data.

Check both the HTTP status and response JSON. Successful backend data is inside `data`; errors contain top-level `code`, `message`, `fieldErrors`, and `correlationId`. Ollama has its own response shape. Inspect **Test Results** or the request's test-results tab after every send. A green HTTP check alone is not enough for C/D; their manual checks are listed below.

An expected `401`, `403`, or `409` in a negative test is a success when its assertion is green. “No response” with a script error means the pre-request script may have stopped the send; it is not necessarily a server error.

### 5.2 Environment inputs versus generated collection state

Keep connection settings, tokens, and deliberate scenario inputs in the environment. The request scripts save runtime IDs, keys, and response snapshots in **collection variables**. An environment variable with the same name overrides that collection value—even an empty environment value can shadow it. Runner data and local variables can override values too.

| Variable family | Meaning; do not interchange these |
| --- | --- |
| `riskFixturePaymentId`, `directCaseId` | Folder 04's SQL observation and evidence-only case |
| `r2PaymentId`, `r2QuoteId`, `r2ConfirmKey` | Folder 05's first, intended LOW payment |
| `r3r5PaymentId`, `r3r5QuoteId`, `r3r5ConfirmKey` | Folder 05's second, intended MEDIUM payment |
| `paymentId`, `caseId` | Current A/B payment and bound review; also used by contextual Copilot |
| `originalQuoteId`, `confirmKey` | Original A confirmation and its exact historical replay |
| `freshQuoteId`, `reconfirmKey` | A's post-approval confirmation and its exact replay |
| `currentPolicyId` | Folder 02's selected immutable current revision |
| `policyId` | C's selected policy; D2 overwrites this with a newly created policy ID |

Having `r2PaymentId` does not automatically populate `paymentId`. Do not use the LOW folder-05 payment as if it were the HIGH A/B review fixture.

### 5.3 How to clear variables, and when NOT to clear them

For a **genuinely new** scenario:

1. Click the collection's top-level name, not a request or the environment.
2. Open its **Variables** tab. Locate each name in the applicable list below.
3. Empty its local/current **Value** cell. Keep the variable name; do not type `null`, quotes, or a space. If your version shows both Initial and Current values, clear any old generated IDs/keys from both.
4. Save the changes if your Postman version offers Save.
5. Open **Fluxpay M5 Local → Variables**. Remove any duplicate rows for these generated names. Do not remove tokens or deliberate input variables.

For a new folder-04 assessment, clear:

```text
directAssessmentId, directCaseId, directAssessmentData,
expectedPaymentFingerprint, assessmentSequence
```

For a fresh folder-05 pair, clear:

```text
r2DraftKey, r2PaymentId, r2QuoteId, r2ConfirmKey, r2CaseId,
r3r5DraftKey, r3r5PaymentId, r3r5QuoteId, r3r5ConfirmKey, r3r5CaseId
```

For a new A or B scenario, save the previous scenario's evidence first, then clear:

```text
draftKey, confirmKey, reconfirmKey, paymentId, paymentStatus,
originalQuoteId, originalQuoteExpiresAt, freshQuoteId, freshQuoteExpiresAt,
caseId, assessmentId, reviewReference, decisionId, deliveryState,
firstConfirmData, processingConfirmData
```

For **resume/retry**, do not reset these lists. Preserve the IDs, original request body, keys, and snapshots; read actual payment/case state first. Clearing variables creates new API operations, not a clean database. It does not erase same-day history. Never blindly use Runner **Rerun** on an old mutating scenario.

## 6. Folder 00 — authorization

**Purpose:** prove signature validation, access to the owner-scoped payment-list endpoint, and ADMIN-only policy access. These four requests do not independently test cross-owner denial. A token problem otherwise can be mistaken for a risk or vector-search bug.

Select `Fluxpay M5 Local`, then send these four saved requests:

| Step | Authentication / endpoint | Expected result | Why test it? |
| --- | --- | --- | --- |
| 1. ADMIN token lists one policy | ADMIN; `GET /api/policies?page=0&size=1` | `200`, successful page envelope | Valid administrators can reach policy APIs |
| 2. USER lists owned payments | USER; `GET /api/payments?page=0&size=1` | `200`; an empty page is valid | Valid users can reach their payment list |
| 3. Unsigned policy request | No Auth; same policy endpoint | `401 AUTH_REQUIRED` | Missing identity is rejected |
| 4. USER cannot administer policies | USER; same policy endpoint | `403 FORBIDDEN` | A valid signature does not grant ADMIN privileges |

Expected total: **4 requests, 8 passing named tests**. Stop on an unexpected failure. For `401`, follow section 17.1; for `403` on the ADMIN check, verify the request uses `adminToken` and that this value came from `-Account admin`. Do not weaken authentication to continue.

**Fresh-database exception:** V708 intentionally leaves canonical hashes to be reconciled. If the signed ADMIN policy-list request returns `503 POLICY_CORPUS_UNRECONCILED`, first send folder **01 request 2** (ADMIN hash reconciliation), repeat it once to verify zero remaining updates, then rerun all of folder 00. This bootstrap step is needed before policy listing on a newly migrated corpus; it is not a JWT failure. On an already-reconciled database, follow the normal order.

## 7. Run folders 01–03: real Oracle vector search

### 7.1 Folder 01 — provider and policy readiness

**Purpose:** prove the real embedding provider works and the correct policy revisions exist before interpreting a retrieval result.

Run `01. Ollama and current-policy readiness` one request at a time:

1. **Warm Ollama** must return `200`; `embeddings[0]` must contain exactly 768 finite values and at least one nonzero value. This request explicitly uses **No Auth**, so the ADMIN JWT is not sent to Ollama.
2. **Reconcile canonical hashes** returns `updatedDocuments >= 0`. Send it a second time; the second response should show `updatedDocuments: 0`.
3. **Verify all 13 V708 current policies exist** uses ADMIN `GET /api/policies?page=0&size=100`. Expect `200` and the explicit 13-ID assertion to pass. The total need not equal 13: superseded originals remain as history, and later D tests may add documents.

The warm-up request is `POST {{ollamaUrl}}/api/embed` with this body:

```json
{
  "model": "{{embeddingModel}}",
  "input": "search_query: sender verification",
  "truncate": false
}
```

The reconciliation request is ADMIN `POST {{baseUrl}}/api/policies/reconcile-hashes`, no body. It deliberately fills missing canonical hashes; it is not purely read-only. Its repeat showing `updatedDocuments: 0` demonstrates stability rather than reintroducing duplicate identity metadata.

Expected initial total: **3 requests, 6 passing named tests**, plus your repeat reconciliation check. An Ollama connection failure requires checking the service/model. A dimension mismatch means the model/configuration pair does not fit the 768-dimensional database contract; do not change the Oracle vector width to hide it. `embeddingModel` in Postman affects warm-up only; backend indexing/search use the backend's own model and embedding-space configuration.

### 7.2 Folder 02 — index all 13 current policies without Runner data

**Purpose:** turn each current immutable policy document into real embeddings, persisted chunks, and an active Oracle vector generation. A document appearing in the list is not proof it is searchable.

There are intentionally only **two requests**: GET one policy to verify its identity, then POST to index that policy. Repeat the pair for all 13 rows below. You do not need to use a paid/data-file Runner feature.

#### One-time manual-mode adjustment in your imported Postman copy

The supplied folder is named `02. Index 13 current policies — run this folder with Runner data only`. Its scripts explicitly read Runner iteration data. Setting environment values alone will not make the unmodified scripts accept manual Send.

1. Open request 1, **Scripts → Pre-request**. Replace every `pm.iterationData.get` with `pm.variables.get`.
2. In that request's **Scripts → Post-response** (called **Tests** in some versions), make the same replacement.
3. Repeat in request 2's Pre-request and Post-response scripts. There are **six occurrences total** across the two requests.
4. Save both requests. Keep every other assertion, the 13-ID allowlist, and the `allowPolicyIndexing` guard intact. If those occurrences were already changed during earlier troubleshooting, do not change them again.

Example of the only code substitution:

```javascript
// Before: reads only an iteration data file
pm.iterationData.get('currentPolicyId')
// After: also resolves the active environment during manual Send
pm.variables.get('currentPolicyId')
```

Do the same for `expectedPolicyTitle`. This is a local Postman test-script adjustment, **not** a backend or vector-database code change. Reimporting the original collection may restore the Runner-only scripts.

#### Repeat this procedure for each row

1. Set environment `allowPolicyIndexing` to `true`.
2. Copy one full UUID into `currentPolicyId` and its exact title into `expectedPolicyTitle`. Use the same row for both values; no added quotes or whitespace.
3. Send request 1: ADMIN `GET {{baseUrl}}/api/policies/{{currentPolicyId}}`.
4. Expect `200`. Confirm `data.id` and `data.title` match the row and both tests pass. **Stop if either differs.** The index script does not itself enforce that the previous GET passed; this operator check is required.
5. Only then send request 2: ADMIN `POST {{baseUrl}}/api/policies/{{currentPolicyId}}/index`, no body.
6. Expect `200` and the evidence fields below. Save that row's result before proceeding: the collection's generation variables retain only the latest response.
7. Move to the next row and repeat steps 2–6. After all 13, set `allowPolicyIndexing=false` again.

| Row | `currentPolicyId` | Exact `expectedPolicyTitle` |
| --- | --- | --- |
| 1 | `00000000-0000-0000-0000-000000006e01` | Current Demo Sender Verification Rule |
| 2 | `00000000-0000-0000-0000-000000006e02` | Current Demo First Recipient Rule |
| 3 | `00000000-0000-0000-0000-000000006e03` | Current Demo Source Currency Value Rule |
| 4 | `00000000-0000-0000-0000-000000006e04` | Current Demo Review and Payment Separation |
| 5 | `00000000-0000-0000-0000-000000006e05` | Current Demo Destination Rule |
| 6 | `00000000-0000-0000-0000-000000006f01` | Demo Enhanced Due Diligence Control Limitations |
| 7 | `00000000-0000-0000-0000-000000006f02` | Current Demo Six Rule Monitoring Scope |
| 8 | `00000000-0000-0000-0000-000000006f03` | Unimplemented Sanctions and PEP Controls |
| 9 | `00000000-0000-0000-0000-000000006f04` | Current Demo Purpose Rule |
| 10 | `00000000-0000-0000-0000-000000006f05` | Current Demo Recipient Today Rule |
| 11 | `00000000-0000-0000-0000-000000006f06` | Demo Recovery Information Boundary |
| 12 | `00000000-0000-0000-0000-000000006f07` | Demo Support and Evidence Boundaries |
| 13 | `00000000-0000-0000-0000-000000006f08` | Demo Identity Document Control Boundary |

Check these index response fields inside `data`:

```text
policyDocumentId=<the selected UUID, not another policy>
mock=false
chunkCount > 0
generationId=<non-empty UUID>
embeddingSpaceId=<non-empty configured-space identifier>
replayed=<boolean>
```

First publication normally reports `replayed=false`; a policy already indexed during earlier testing can correctly report `true`. Repeat one unchanged index request and verify `replayed=true` with the same generation and chunk count. GET its detail again: expect `indexState=INDEXED` and an active generation. This checks that retries reuse immutable work instead of duplicating chunks. Short documents may have one chunk even though the configured normal chunk target is 400–700 words with 50-word overlap.

Expected base total: **26 sends, 52 passing named tests** across 13 pairs, plus the optional replay/detail checks. Keep a per-policy note of ID, chunk count, generation, space, and `mock=false`.

#### Alternative: Runner, if data-file selection is available to you

Select only folder 02, select `Fluxpay M5 Local`, load [m5-current-policy-ids.json](postman/m5-current-policy-ids.json), confirm **13 iterations**, and run with indexing explicitly enabled. Inspect every ID/title and indexing result; then disable indexing. The original scripts work with this data file; the manual adjustment is unnecessary for this route.

Do not run the entire collection with the 13-row data file. That would repeat payment and review mutations 13 times.

### 7.3 Folder 03 — prove grounding and the no-answer path

**Purpose:** distinguish an actually useful, grounded search from a service that merely returns HTTP 200 or labels itself non-mock.

Both requests use ADMIN `POST {{baseUrl}}/api/copilot/ask`, JSON body, with `paymentId: null`:

1. The sender-verification question must return `200`, `mock=false`, a nonempty answer, and one or more sources with policy IDs, titles, and excerpts.
2. The chocolate-cake question must return exactly `No grounded answer found in current policies.`, `mock=false`, and an empty sources array.

The saved questions are `When must a sender undergo manual review because their identity is unverified?` and `How do I bake a chocolate cake?`. Expected total: **2 requests, 4 passing named tests**. Inspect the relevant sources manually against section 7.2's current IDs and titles; the scripts do not assert membership in that full 13-ID set. Complete this evidence before publishing extra policies in D.

Together with indexing evidence, these outcomes exercise real provider embeddings, active current Oracle vector generations, relevant citations, and the no-answer threshold. The current answer is a selected policy excerpt, not evidence of a separate generative model reasoning about or authorizing the payment.

If the relevant request returns no answer, check that all 13 policies indexed with `mock=false`, that backend indexing and search use the same embedding space, and that Ollama is warm. The current retrieval uses top-K 5 and maximum distance 0.35. Do not loosen the cutoff merely to force a passing response.

## 8. Folder 04 — direct authoritative risk and replay

**Purpose:** prove risk and reasons come from the database, not the caller; prove assessment replay is stable; and prove raw evidence cannot authorize a payment. This separately tests R1 `KYC_UNVERIFIED`, since M3 correctly blocks an unverified sender from the normal draft flow.

Prerequisites:

- Run `docs/sql/m5-risk-demo-fixture.sql` once.
- Set `riskFixturePaymentId` in the private Postman environment.
- Clear `directAssessmentId`, `directCaseId`, `directAssessmentData`, `expectedPaymentFingerprint`, and `assessmentSequence` from collection Current values before a new fixture assessment.

All six requests use `adminToken`. Send them in this order:

| Step / endpoint | Expected result | Why is it necessary? |
| --- | --- | --- |
| 1. `GET /api/compliance/payments/{{riskFixturePaymentId}}/assessment-context` | `200`; a 64-character fingerprint and `nextAssessmentSequence` | Captures the server's current facts and ordering, not a client guess |
| 2. `POST /api/compliance/assess/{{riskFixturePaymentId}}` | `201`; `HIGH`, `REVIEW`; reasons include R1, R2, R4 | Pending KYC, no completed recipient history, and USD 1500 produce stored risk evidence |
| 3. Exact replay of step 2 | `200`; identical stored `data` | A retry cannot produce a different result for the same assessment identity |
| 4. Same assessment ID, changed fingerprint | `409 ASSESSMENT_CONFLICT` | Reusing an identity for different input must be rejected |
| 5. `GET /api/compliance/cases/{{directCaseId}}` | `200`; `reviewable=false`, null disposition/reference | Direct assessment is evidence, not an active M3 payment hold |
| 6. `PUT /api/compliance/cases/{{directCaseId}}/approve` | `409 CASE_CONFLICT` | An ADMIN cannot approve an unbound evidence case as if it controlled money |

The request body contains only assessment identity, sequence, and the server-provided fingerprint:

```text
{
  "assessmentId": "{{directAssessmentId}}",
  "assessmentSequence": {{assessmentSequence}},
  "expectedPaymentFingerprint": "{{expectedPaymentFingerprint}}"
}
```

Leave this saved JSON template as supplied. Step 1 captures `expectedPaymentFingerprint` and the next `assessmentSequence`; step 2 generates `directAssessmentId` and saves `directCaseId` and `directAssessmentData`. **Do not hardcode the sequence to 1** on reruns. The body never contains `risk`, `verdict`, or `reasons`.

Expected total: **6 requests, 12 passing named tests**. Replay compares `data`, not the entire envelope: a new `correlationId` is normal. A direct case can display case status `UNDER_REVIEW` while the SQL fixture payment is still `DRAFT`; `reviewable=false`, null `paymentDisposition`, and null `reviewReference` are the crucial distinction. Only integrated M3 confirmation binds an active `REVIEW_REQUIRED` hold.

If facts changed or the sequence became stale, `409 STALE_ASSESSMENT` is different from deliberate `ASSESSMENT_CONFLICT`: start a new assessment with fresh context and a new assessment UUID, then redo the replay checks. Do not change IDs or fingerprints midway through the deliberate exact-replay test.

## 9. Folder 05 — isolated LOW and MEDIUM risk scenarios

**Purpose:** prove that one MEDIUM-severity reason can still produce overall LOW risk, while multiple MEDIUM reasons cause review. It also checks actual same-day history and stored purpose values rather than fabricated risk inputs.

For a first run on a fresh `m5-backend-test-data.sql` fixture, use the safe India recipient `5f200000-0000-0000-0000-000000000001` before making any other payment to it. If this recipient already has payment history, follow **Recover folder 05 after failed attempts** below to prepare a fresh recipient. This folder uses its own variables and does not overwrite folder A's payment.

Before a genuinely fresh run, clear these collection Current values:

```text
r2DraftKey, r2PaymentId, r2QuoteId, r2ConfirmKey, r2CaseId,
r3r5DraftKey, r3r5PaymentId, r3r5QuoteId, r3r5ConfirmKey, r3r5CaseId
```

All eight requests use `userToken`. A refreshed `adminToken` does not refresh this token; check folder 00 request 2 before starting if the USER token may have expired.

Run the eight requests once, one at a time, in order. Check each response and its tests before sending the next request; stop if a result is unexpected. No Runner data file or paid Postman feature is needed.

| Request | Expected result | Why is it necessary? |
| --- | --- | --- |
| 1. Create first payment | `201 DRAFT`; USD `100`, `FAMILY_SUPPORT`, safe India recipient; saves `r2PaymentId` | Isolates recipient novelty while avoiding value, purpose, destination, and KYC triggers |
| 2. Obtain first quote | `201`; saves `r2QuoteId`; send request 3 immediately | Confirmation must use a live server-created quote |
| 3. Confirm first payment | `200 PROCESSING` using `r2ConfirmKey` | R2 alone must not unnecessarily hold the payment |
| 4. Inspect first passport | `200`; only `FIRST_TO_RECIPIENT`; `LOW`, `APPROVE`, `PROCEED`, `reviewable=false` | Proves the reason and aggregate outcome, not just the HTTP status |
| 5. Create second payment | `201 DRAFT`; USD `100`, `BUSINESS`, same recipient; saves `r3r5PaymentId` | Establishes the second same-day attempt with a short stored purpose |
| 6. Obtain second quote | `201`; saves `r3r5QuoteId`; send request 7 immediately | Gives the second payment its own valid quote |
| 7. Confirm second payment | `202 UNDER_REVIEW` using `r3r5ConfirmKey` | Multiple MEDIUM reasons must enter a bound review |
| 8. Inspect second passport | `200`; `FIRST_TO_RECIPIENT`, `RECIPIENT_TODAY`, `SHORT_PURPOSE`; `MEDIUM`, `REVIEW`, `REVIEW_REQUIRED`, `reviewable=true` | Confirms exactly why the result differs from the first payment |

The saved requests call `POST /api/payments/draft`, then `POST /api/payments/{the corresponding payment ID}/quotes`, then `/confirm`, then `GET /api/compliance/payments/{that ID}/passport`. Do not substitute A's `paymentId` or quote variables into these requests.

A successful run passes all 16 named Postman tests. Complete both payments on the same calendar day in the configured `Asia/Kolkata` zone.

The first payment remains `PROCESSING`, not `COMPLETED`, so R2 still applies to the second payment. `ALL_ATTEMPTS` history mode lets that first payment establish R3. `BUSINESS` has fewer than ten characters and establishes R5 under the current enum-based purpose contract.

If step 4 already contains R3, this recipient has prior same-day history and the targeted precondition is not clean. Use a newly seeded safe recipient or the explicitly disposable fresh local schema; do not edit returned reasons or delete shared/team data just to make the assertion pass.

### Recover folder 05 after failed attempts

In the recorded failed run, the original safe recipient already had payment history. Clearing Postman variables does not remove that history. Under `ALL_ATTEMPTS`, even another draft whose confirmation failed counts toward R3. A new assessment therefore can correctly produce `MEDIUM/REVIEW` instead of the intended isolated R2-only `LOW/APPROVE`. Canceling the second payment does not restore clean history either.

After resolving the confirmation error, prepare one fresh recipient for the complete eight-request proof:

1. If you encountered the immediate-expiry timestamp bug described below, restart the backend with the corrected configuration before continuing.
2. From the repository root, run the command below once. It connects to local `FLUXPAY/FREEPDB1` and creates one new synthetic safe recipient; copy the `safeRecipientId` it prints.
3. In Postman, select your private M5 environment and paste that printed ID into its `safeRecipientId` value. Keep this same recipient for all eight requests.
4. Open the collection's **Variables** tab and clear the ten generated values listed above once. In the active environment, remove any variables with those same ten names so they cannot override the values saved by the collection. Keep `safeRecipientId`, `sourceWalletId`, `baseUrl`, and your private tokens.
5. Open folder 05 and send requests 1 through 8 once, following the table above. Finish requests 1–4 before creating the second draft. Do not clear generated variables between requests or rerun the whole folder to recover an individual failure.

This prepares a new scenario without deleting the earlier payments or their screening evidence. It does not establish that the new run has passed; verify all eight responses and 16 tests in Postman.

```powershell
sqlplus -L fluxpay@localhost:1521/FREEPDB1 @docs/sql/m5-folder05-fresh-recipient.sql
```

The helper checks the local target and synthetic sender prerequisites. It does not reset balances, alter old payments, or insert risk outcomes. Each invocation creates a different recipient. Do not reuse a UUID from an earlier screenshot as a supposedly fresh recipient.

## 10. Folder A — HIGH risk review, approval, and safe reconfirmation

**Purpose:** prove the real collaboration boundary: M3 holds a risky payment, M5 persists an officer decision, M3 acknowledges it, and the sender explicitly reconfirms before any posting. Approval is not itself a debit or a payout.

For a new scenario, clear the A/B generated values in section 5.3 once. For a paused scenario, use section 17 instead. Check both signed folder-00 requests and set these inputs:

```text
sourceWalletId = 5f100000-0000-0000-0000-000000000001
recipientId    = 5f200000-0000-0000-0000-000000000002
sourceAmount   = 1500.0000
purpose        = FAMILY_SUPPORT
preference     = CHEAPEST
```

The amount triggers R4 and the synthetic RU recipient triggers R6, so the outcome is deterministically `HIGH` even if payment history changes.

`purpose` and `preference` above describe the saved draft body: they are literal `FAMILY_SUPPORT` and `CHEAPEST` values in A1, not environment substitutions. The other three inputs are resolved variables.

Run stepwise, not in Runner. **A5/A6 use ADMIN; the other nine requests use USER.**

| Step | Method / endpoint | Expected result and captured state | Why test it? |
| --- | --- | --- | --- |
| A1. Create owned draft | `POST /api/payments/draft` | `201 DRAFT`; captures `paymentId`, `paymentStatus` | Establishes a persisted owned payment using actual M1/M2/M3 data |
| A2. Obtain original quote | `POST /api/payments/{{paymentId}}/quotes` | `201`; three routes, one recommended; captures `originalQuoteId`, expiry | Screening occurs as part of confirming a real quote, not a fabricated route |
| A3. Confirm original quote | `POST /api/payments/{{paymentId}}/confirm` | Send immediately; `202 UNDER_REVIEW`; captures `firstConfirmData` | HIGH risk must stop confirmation before posting |
| A4. Owner passport | `GET /api/compliance/payments/{{paymentId}}/passport` | `200`; `HIGH`, `REVIEW_REQUIRED`, `reviewable=true`; captures current `caseId`, assessment/reference | Connects the payment hold to authoritative R4/R6 evidence |
| A5. ADMIN approve | `PUT /api/compliance/cases/{{caseId}}/approve` | `200`; case `APPROVED`, verdict `APPROVE`, decision ID, delivery `PENDING` or `ACKNOWLEDGED` | Persists the officer's reason and decision durably |
| A6. Inspect delivery | `GET /api/compliance/cases/{{caseId}}` | `200`; repeat until **`deliveryState=ACKNOWLEDGED`** | Proves M3 accepted the decision, not merely that M5 stored it |
| A7. Owner payment | `GET /api/payments/{{paymentId}}` | `200 DRAFT` after delivery | Proves approval unlocked a new confirmation attempt, not an automatic payout |
| A8. Fresh quote | `POST /api/payments/{{paymentId}}/quotes` | `201`; `freshQuoteId` differs from original; captures fresh expiry | Old price/route information must not be reused for a new confirmation |
| A9. Reconfirm | `POST /api/payments/{{paymentId}}/confirm` | **`200 PROCESSING`**, captures `processingConfirmData` | Fresh quote plus valid approval receipt allows the normal funds/posting boundary |
| A10. Replay original confirmation | Same confirm endpoint, original key/body | `202`; `data` equals `firstConfirmData` | A retry returns its original outcome even though current state advanced |
| A11. Replay successful reconfirmation | Same endpoint, fresh key/body | `200`; `data` equals `processingConfirmData` | A retry must not create another debit or new confirmation |

At A4, inspect both `HIGH_RISK_DEST` and `HIGH_VALUE` in the reasons. Additional history reasons may appear; the HIGH destination reason still determines overall HIGH risk. A5's saved reason is `Synthetic evidence reviewed`. Do not change it to real approval claims about a person or transaction.

### 10.1 The required delivery pause

After A5, repeat A6 about every 10 seconds. Its ordinary tests may be green while delivery is still `PENDING`; **read the field explicitly**. The worker normally checks every 10 seconds, but retry backoff can take longer. Do not promise a fixed 10-second completion.

Only after acknowledgment, send A7 and verify `DRAFT`. If it remains `UNDER_REVIEW`, recheck A6 and the exact payment/case binding. Do not run A2 on a held payment or manually set `paymentStatus=DRAFT` in Postman to bypass a guard.

Then do A8 and A9 promptly. Quotes last 15 minutes. The one-use approval receipt independently lasts **900 seconds from M3 acceptance**. A new quote does not renew the approval. A JWT lasts one hour. These are three separate expiry checks.

If A9 returns `202 UNDER_REVIEW`, it has not passed: a new review is needed. If a request shows “No response,” its pre-request guard may have blocked it. See section 17 before resetting anything.

### 10.2 Keep idempotency keys paired with their original bodies

The saved scripts generate keys once when their collection values are empty. For exact replay, preserve the entire pairing:

| Requests | Header `Idempotency-Key` | JSON body | Expected replay |
| --- | --- | --- | --- |
| A3 and A10 | `{{confirmKey}}` | `{"quoteId":"{{originalQuoteId}}"}` | Historical `202 UNDER_REVIEW` |
| A9 and A11 | `{{reconfirmKey}}` | `{"quoteId":"{{freshQuoteId}}"}` | Historical `200 PROCESSING` |

Both pairs must also keep the same `paymentId`. The two keys must differ. Never use a key from `r2ConfirmKey`, another payment, or another quote. Do not put an automatically changing `{{$guid}}` directly into a replay header. Do not click Postman's suggested **Regenerate idempotency key / Apply Fix** blindly.

A10 returning `UNDER_REVIEW` does not move the current payment backward. Read the current payment separately: after successful A9 it remains `PROCESSING`. A11's response equality is one layer of proof; section 16's ledger check supplies the no-duplicate-posting evidence.

## 11. Folder B — separate rejection path

**Purpose:** prove rejection is a durable alternative to approval and cannot accidentally release or debit a held payment. Approval and rejection are mutually exclusive decisions, so use a **new payment and a new undecided case**:

1. Clear the generated A-flow variables listed in section 5.
2. Run A1 through A4 only to create a new active `UNDER_REVIEW` case.
3. Do **not** run A5.
4. Run B1 using ADMIN: `PUT /api/compliance/cases/{{caseId}}/reject`, with the saved reason `Synthetic evidence insufficient`. Expect `200`, case `REJECTED`, verdict `BLOCK`, and a durable decision. This is the officer's rejection decision, not a claim that the original rule engine returned BLOCK.
5. Repeat B2, ADMIN `GET /api/compliance/cases/{{caseId}}`, until `deliveryState=ACKNOWLEDGED`. This proves the rejection crossed the M5–M3 boundary.
6. Run B3, USER `GET /api/payments/{{paymentId}}`. Expect `200` with payment status `REJECTED`. Its guard needs actual acknowledgment first.
7. Use section 16 to verify no `m3:<this payment UUID>` journal was posted. B3's HTTP/state assertion alone does not inspect the ledger.

A rejected review has no posting path, so no refund is required: the original confirmation never debited the sender. Refund testing belongs to the payment/payout owner for money that was actually posted or settled.

Do not reuse A's approved case to test rejection, run B as part of A's approval sequence, or clear the case ID between B1 and B3. If the case is already decided, use the saved outcome as evidence and create a genuinely new scenario for the opposite decision.

## 12. Folder C — read-only operator checks and Copilot context

**Purpose:** demonstrate the APIs an operator uses to inspect reviewable cases and policies, and show that policy answers and stored payment context remain separate from risk decisions. All five requests use `adminToken`. They read application state; the two Copilot POST requests do not publish policies or reassess payments.

### 12.1 Prepare the inputs

1. Refresh/check ADMIN authentication if needed.
2. Keep the `paymentId` from an assessed A or B payment. If using a different payment deliberately, read its passport first and record its ID; do not confuse it with `r2PaymentId` or `riskFixturePaymentId`.
3. Set the collection `question` to `What checks still apply after a manual compliance approval?`, or keep its supplied value. Remove any unintended environment override of `question`.
4. For policy detail, choose an actual ID from C2's list and set collection `policyId`. The current sender-verification policy is `00000000-0000-0000-0000-000000006e01`. Remove unintended environment `policyId` overrides.

### 12.2 Send all five requests and inspect the actual fields

| Step | Endpoint / body | Expected result | Why is it necessary? |
| --- | --- | --- | --- |
| C1. List reviewable cases | `GET /api/compliance/cases?reviewable=true&page=0&size=20` | `200`; returned cases are currently reviewable; an empty list is valid after decisions | Operators need the active review queue, not every historical evidence case |
| C2. List policies | `GET /api/policies?page=0&size=100` | `200`; inspect `data.content` and current/historical document identity | Confirms reference material exists and lets you select the intended policy |
| C3. Policy detail | `GET /api/policies/{{policyId}}` | `200`; ID/title match; inspect index state and active generation | A policy row alone does not prove that exact revision is indexed |
| C4. Copilot with stored context | `POST /api/copilot/ask`; saved body uses `questionJson` and `paymentId` | `200`, `mock=false`; relevant answer/sources, stored `caseContext` for the selected payment | Operators can see grounded references alongside authoritative case facts |
| C5. Copilot without context | Same endpoint, `paymentId: null` | `200`, `mock=false`; grounded response or explicit no-answer; empty `caseContext` | General policy lookup must work without silently attaching another payment's facts |

The saved C4 request body is a template:

```text
{"question": {{questionJson}}, "paymentId": "{{paymentId}}"}
```

Its pre-request script JSON-serializes `question` into `questionJson`; do not add extra quotes around `{{questionJson}}`. C5 uses the same template with `"paymentId": null`.

For C4, check that context belongs to the selected payment/case and that its risk/reasons agree with the stored passport. `caseContext.status` is a **case** status, not the M3 payment status. An approved case and a `PROCESSING` payment are compatible.

The current implementation retrieves by the question and returns case context separately. It does not feed a model-generated risk decision back into the payment. A no-answer response can be valid for a question outside the corpus. C's scripts mostly check HTTP status and correlation, so use the stronger relevant/unrelated checks in folder 03 and inspect context yourself; green basic tests alone do not prove semantic correctness.

Record the selected policy ID, payment/case ID, question, answer, and source IDs without exporting tokens.

## 13. Folder D — deliberate policy publication and indexing

**Purpose:** test the policy write lifecycle separately from reads: canonical identity, immutable publication, real indexing, and safe repeated indexing.

**Persistent-change warning:** D2 creates a policy and D3 makes it eligible for retrieval. The resulting corpus is no longer just the 13 current demo policies; search rankings or answers can change. No delete/unindex cleanup endpoint is supplied. Finish the 13-policy and folder-03 evidence first. If that database must retain exactly the original corpus, skip D2/D3 there and test publication in a separately configured disposable local database. Do not delete existing team data for cleanup.

`allowPolicyIndexing=false` guards **folder 02 only**. It does not block D3. Run D individually and only when these mutations are intended.

### D1. Reconcile canonical hashes

1. Use ADMIN `POST {{baseUrl}}/api/policies/reconcile-hashes`, no body.
2. Expect `200`; inspect `data.updatedDocuments`, which must be nonnegative.
3. Send again: expect zero updates. If folder 01 already reconciled everything, zero on the first send is normal.

Why: canonical content hashes support deduplication. The reconciler fills missing hash metadata after checking for conflicts; it does not rewrite policy text, vectors, or generation history. A collision or mismatched existing hash returns a structured conflict rather than silently replacing evidence.

### D2. Create an immutable synthetic policy

1. Remove any environment override of `policyId`; D2 must be able to save its new ID into collection state for D3.
2. Inspect collection `policyTitle` and `policyContent`. The supplied title is `Synthetic M5 integration policy`. The content explains synthetic manual approval, fresh confirmation, remaining checks, and the `PROCESSING` boundary; it is test reference text, not an instruction to change risk rules.
3. Send the saved ADMIN `POST {{baseUrl}}/api/policies` request. The body uses:

```text
{
  "title": "{{policyTitle}}",
  "category": "SUPPORT",
  "content": {{policyContentJson}}
}
```

4. The pre-request script serializes `policyContent`; do not add quotes around `{{policyContentJson}}` or place the content in query parameters.
5. For new content, expect `201`. Record `data.id`. Confirm a new document is `UNINDEXED`, with zero chunks and no active generation. The post-response script saves `policyId`.

Why: publication establishes a persistent immutable document before the separate embedding operation. It must not masquerade as already searchable.

If identical normalized content was created during an earlier run, `409 DUPLICATE_POLICY` is the intended deduplication result; `fieldErrors.existingPolicyDocumentId` identifies the existing document. Changing only the title does not make the content unique. Inspect the existing document, and deliberately set `policyId` to that ID if continuing with its indexing. The original D2 success test expects a new `201`, so label a duplicate result as **duplicate protection verified**, not as a fresh-create pass. Do not continue D3 with a stale unrelated `policyId` after any failed D2.

### D3. Index the selected policy and verify replay

1. Verify the resolved URL is ADMIN `POST {{baseUrl}}/api/policies/{{policyId}}/index` for the exact ID from D2 or the deliberately inspected existing document. No body is needed.
2. Send. Expect `200`; manually verify `policyDocumentId` matches, `mock=false`, `chunkCount > 0`, and nonempty `generationId`/`embeddingSpaceId`.
3. For a first publication into the configured space, expect `replayed=false`. For an already-indexed unchanged policy, `true` is valid. The supplied short synthetic policy can have a single chunk.
4. Send the unchanged request again. Expect `replayed=true` with the same generation and chunk count. Read policy detail in C3 and verify `INDEXED` and the active generation.

Why: real indexing exercises chunking, provider embeddings, Oracle vector persistence, and activation; replay proves a retry does not duplicate immutable generations. D3's basic green tests do not check all these fields, so save the manual evidence.

After D, record the additional policy ID for future runs and restore `policyId` to your intended C selection if needed. Creating policy text does **not** implement new R1–R6 rules or add sanctions/PEP enforcement. Existing policy vectors are not deliberately rewritten by this new-document path, but the extra searchable document can affect retrieval results.

## 14. What each risk reason comes from

The engine evaluates rules in fixed R1–R6 order. Reason severity and overall risk are different concepts: any HIGH reason makes overall risk HIGH; otherwise two or more MEDIUM reasons make overall risk MEDIUM; zero or one MEDIUM reason leaves overall risk LOW.

| Rule and reason | Authoritative source | Postman setup | Expected effect |
| --- | --- | --- | --- |
| R1 `KYC_UNVERIFIED` | Latest stored sender KYC observation | Optional SQL R1 fixture, then folder 04 | HIGH reason → overall HIGH/REVIEW |
| R2 `FIRST_TO_RECIPIENT` | No earlier completed payment for sender/recipient, excluding current payment | First payment to a fresh recipient | MEDIUM reason; alone overall LOW/APPROVE |
| R3 `RECIPIENT_TODAY` | At least one other qualifying same-day payment under configured `ALL_ATTEMPTS` mode | Create a second same-day payment to the same recipient | MEDIUM reason; often combines with R2 → MEDIUM/REVIEW |
| R4 `HIGH_VALUE` | Source amount strictly greater than configured source-currency threshold | USD `1500.0000` where threshold is USD `1000` | MEDIUM reason; exactly `1000` does not trigger |
| R5 `SHORT_PURPOSE` | Persisted purpose text has fewer than 10 Unicode code points after trimming | Change draft purpose to `EDUCATION`, `BUSINESS`, or `SAVINGS` | MEDIUM reason; `FAMILY_SUPPORT` avoids it |
| R6 `HIGH_RISK_DEST` | Frozen recipient snapshot country appears in configured demo set | Synthetic recipient `...0002` has country `RU` | HIGH reason → overall HIGH/REVIEW |

The current shared payment contract stores a `PaymentPurpose` enum, not a free-form narrative. Therefore R5 currently measures enum text length. If the intended product rule is “customer explanation quality,” Member 3 must add a server-stored narrative-purpose field to the shared payment model; M5 should consume that field rather than exposing an endpoint that accepts an untrusted risk hint.

## 15. Policy realism and current limitations

V708 supplies 13 current immutable policy revisions. They are sufficient for the prototype because they accurately describe the implemented six rules, review/payment separation, vector-grounding boundaries, and controls that are explicitly not implemented. Superseded text remains in Oracle for evidence but is excluded from current retrieval.

They are not enough to claim production compliance. A real deployment would need jurisdiction-specific, professionally approved policy and matching implemented controls for areas such as sanctions/PEP and adverse-media screening, ongoing KYC and document expiry, source of funds/wealth, transaction velocity and structuring, device/account-takeover signals, regulatory reporting, reviewer escalation/SLAs, evidence retention, and model/retrieval governance.

Adding those topics only as vector-search documents would improve operator reference material but would **not** create enforcement. Each operational policy needs authoritative data, a deterministic or validated decision service, persistence/audit evidence, failure handling, tests, and an owning team. The current `Unimplemented Sanctions and PEP Controls` revision is intentionally honest about that boundary.

## 16. Read-only Oracle evidence

**Purpose:** prove outcomes were stored and that replay did not post money twice. A green HTTP response cannot by itself prove ledger balance or absence of duplicate events.

Open a separate SQL*Plus connection:

```powershell
sqlplus -L fluxpay@localhost:1521/FREEPDB1
```

Enter the password at the prompt. Start with these read-only checks:

```sql
SELECT "version", "description", "success"
FROM "flyway_schema_history"
ORDER BY "installed_rank";

SELECT LOWER(RAWTOHEX(id)) id, title, index_state, chunk_count,
       LOWER(RAWTOHEX(active_generation_id)) generation_id, embedding_space_id
FROM policy_documents
WHERE superseded_by_id IS NULL
ORDER BY id;

SELECT LOWER(RAWTOHEX(payment_id)) payment_id, risk, status,
       screening_verdict, payment_disposition, risk_reasons
FROM screening_cases
ORDER BY created_at DESC;

SELECT LOWER(RAWTOHEX(id)) decision_id, decision, delivery_state, retry_count
FROM m5_review_decisions
ORDER BY decided_at DESC;

SELECT journal_reference, currency, COUNT(*) entries,
       SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END) imbalance
FROM ledger_entries
WHERE journal_reference LIKE 'm3:%'
GROUP BY journal_reference, currency;
```

Policy IDs in `RAWTOHEX` output omit hyphens; compare them to the same UUID with hyphens removed. All 13 current revisions should show `INDEXED`, positive chunks, and active generation/space after folder 02. More current rows may exist if D was run.

For payment-specific checks, replace the placeholder below with the UUID you captured, without braces. Do not reuse an example payment from an old screenshot:

```sql
DEFINE m5_payment_id = 'PASTE-YOUR-PAYMENT-UUID-HERE'

SELECT status, amount AS source_amount, currency AS source_currency
FROM payments
WHERE id = HEXTORAW(REPLACE('&m5_payment_id', '-', ''));

SELECT COUNT(*) AS journal_entries
FROM ledger_entries
WHERE journal_reference = 'm3:' || LOWER('&m5_payment_id');

SELECT currency, COUNT(*) AS entries,
       SUM(CASE WHEN entry_type='DEBIT' THEN amount ELSE -amount END) AS imbalance
FROM ledger_entries
WHERE journal_reference = 'm3:' || LOWER('&m5_payment_id')
GROUP BY currency;

SELECT e.topic, d.state, COUNT(*) AS events
FROM m3_outbox_delivery d
JOIN outbox_events e ON e.id = d.event_id
WHERE d.payment_id = HEXTORAW(REPLACE('&m5_payment_id', '-', ''))
  AND e.topic = 'payment.initiated'
GROUP BY e.topic, d.state;

SELECT LOWER(RAWTOHEX(id)) AS decision_id, decision, accepted_at,
       receipt_expires_at, receipt_consumed_at
FROM m3_review_decisions
WHERE payment_id = HEXTORAW(REPLACE('&m5_payment_id', '-', ''))
ORDER BY accepted_at DESC;

SELECT SYSTIMESTAMP AT TIME ZONE 'UTC' AS now_utc FROM dual;
```

Interpretation:

- While held for review, and after B rejection, the payment journal count must be **zero** and there must be no initiated-payment posting event for that new payment.
- After A9 or folder-05 LOW confirmation, the journal must exist and every currency's `imbalance` must be **zero**. A journal contains multiple balanced entries; do not expect one ledger row.
- Record journal count and initiated-event count after A9, then repeat after A10/A11. Counts and balances must remain unchanged. For this single successful confirmation, expect one initiated event. Outbox presence proves durable intent, not Kafka delivery or settlement.
- M3 receipt times let you distinguish an acknowledged-but-expired approval from a delivery failure. Compare instants/time-zone offsets, not unlabelled local clock strings. A consumed receipt is not available for another new confirmation.

For a broader read-only inspection, use [m5-oracle-verification.sql](m5-oracle-verification.sql) in the explicitly selected local connection. Do not use UPDATE/DELETE to force expected statuses, reasons, balances, or receipts.

## 17. Recovery guide for the errors encountered

Before changing anything, record the failing request name, resolved URL, status, response `code`, and `correlationId`. For confirmation problems, also preserve the payment ID, quote ID, idempotency key, and original body. These test IDs are not bearer credentials; nevertheless do not export private tokens or database secrets.

### 17.1 Unexpected `401 AUTH_REQUIRED` or `403 FORBIDDEN`

1. Verify the active environment is **Fluxpay M5 Local**, including Runner's environment if using it for folder 02. The earlier run with environment `none` could not use the private token values.
2. Identify the token the failing request actually uses: A5/A6, B1/B2, C, D, and direct risk use ADMIN; payment creation/confirmation and owner passports use USER. Folder 05 uses USER only.
3. Regenerate that token using section 4 and paste the raw value into the correct private environment field. Refresh the other token too if it may have expired. A Vault label or unresolved `{{variable}}` is not a JWT.
4. Rerun folder 00's corresponding signed GET. Expect `200` before resuming the failing mutation.
5. Resume the interrupted request with its existing IDs, body, and key. Do not clear the payment or create a new draft just to refresh authentication.

Restart the backend only if it needs a changed secret/configuration loaded. Merely issuing a fresh JWT requires no restart. If `.env`'s secret changed, an old backend process and a token generated with the new secret will disagree. Conversely, the helper can inherit an old process secret unless cleared as in section 4.

`401` means authentication failed; `403` means the authenticated identity lacks permission. Do not treat normal expiry, missing environment, wrong ADMIN/USER selection, and the earlier Oracle mapping issue as one universal cause.

### 17.2 `409 IDEMPOTENCY_CONFLICT`

The key was already paired with different normalized input, possibly another quote or payment. It does not mean risk screening failed.

1. Read the actual payment with USER `GET /api/payments/{the intended UUID}`. If a previous result was a timeout, do not assume it did not commit.
2. For an **exact retry**, restore the original key, payment ID, quote ID, and body together. Keep A3/A10 separate from A9/A11 as in section 10.2.
3. For a **deliberately new** confirmation using a new quote, clear only the corresponding generated key (`confirmKey` before an initial confirmation, or `reconfirmKey` for a post-approval confirmation) once. Its script generates a fresh UUID. Remove an unintended environment override of that key.
4. Check the resolved header/body and send only when the actual payment state permits that new operation.

Do not regenerate keys simply to make a conflict disappear or to recover an unknown outcome. If the payment is already `PROCESSING`, read its current state or replay its known successful pair; do not initiate another new confirmation.

### 17.3 A `201 DRAFT` response shows the old payment again

Draft creation is idempotent. A reused `draftKey` can return the original stored `201 DRAFT` response even when the actual payment has since become `UNDER_REVIEW` or `PROCESSING`. This explains why repeating an entire folder can appear to create a draft yet subsequently fail quoting.

Read the actual payment and compare its ID, recipient, and current status. For a genuinely new scenario, save previous evidence, clear that scenario's complete generated-variable list, and remove environment duplicates. For folder 05 also use fresh recipient history when required. For a paused scenario, retain the old IDs and resume from its actual state.

### 17.4 `410 Gone / QUOTE_EXPIRED`

A quote's normal lifetime is 15 minutes. Compare `expiresAt` with `serverTime` and verify the confirmation really used that response's recommended quote, not an older overridden variable.

The earlier immediate-expiry bug was an Oracle time-zone conversion problem: expiry persisted 5 hours 30 minutes earlier than intended. The current M5 configuration starts Oracle sessions in UTC, alongside the legacy timestamp mapping. Restart an older backend to load it; old quote rows are not repaired. Do not assume every later 410 is this bug—an actually expired or stale selected quote produces the same status.

Safe recovery depends on state:

- **Before the first review, actual payment DRAFT/QUOTED:** get a new quote (A2 for A), inspect the captured ID/expiry, and use a new confirmation key for the intentionally changed quote body. Send promptly.
- **After approval, actual payment DRAFT and acknowledged:** A8 then A9 with a fresh key is the normal path, provided the approval receipt is still valid.
- **After approval, actual payment QUOTED:** if the saved fresh quote remains live, use A9 directly. If it expired, use the temporary refresh request below; do not overwrite the original replay pair or falsify payment status.
- **Actual payment UNDER_REVIEW:** finish the current decision/delivery first. Requesting another quote is not a way to bypass a hold.
- **Folder 05 after several failed attempts:** fix the underlying expiry/configuration problem, then use its fresh-recipient recovery for a clean isolated-risk proof. Old attempts remain legitimate history.

Temporary refresh request for the acknowledged, still-valid approval with a QUOTED payment:

1. Create a new request outside the guarded A sequence: USER Bearer `{{userToken}}`, method POST, URL `{{baseUrl}}/api/payments/{{paymentId}}/quotes`, **no body and no Idempotency-Key header**.
2. Expect `201`. Copy `data.recommendedQuoteId` into collection `freshQuoteId` and `data.expiresAt` into `freshQuoteExpiresAt`; remove any environment duplicates.
3. Preserve `originalQuoteId`, `confirmKey`, and `firstConfirmData`. A10 still needs them.
4. If this is a changed confirmation payload, clear the previously used `reconfirmKey` once, then send A9 promptly. A new quote does not renew an expired approval; see 17.6.

Why not simply rerun A8? Its local guard expects cached `paymentStatus=DRAFT`. After quoting, the server is QUOTED. Running A7 then records QUOTED and fails its DRAFT assertion, which prevents A8 even though the quote service accepts both DRAFT and QUOTED. A temporary request handles this legitimate state without lying to the guard.

A 410 does not complete posting or bind a new active hold. Screening may already have persisted evidence before quote validation; that evidence can have `reviewable=false` and no disposition. Do not approve such a case to work around expiry.

### 17.5 `UNDER_REVIEW` after approval, `409` quoting, or “No response”

- A5 success stores an M5 decision; it does not mean M3 has received it. Poll A6 until the actual `deliveryState` is `ACKNOWLEDGED`, then A7 until the payment is DRAFT.
- A5 returning `401` did not record approval. Refresh ADMIN, verify folder 00, and resume A5—not A1.
- A2 on an actually held payment can return `409 INVALID_PAYMENT_STATE`. Do not rerun the draft/quote steps for that hold.
- A8 or B3 may have **no response** because their pre-request state guard failed before any network send. Inspect the script error, then fetch the real case/payment state with A6/A7 or B2. Do not manually force `deliveryState` or `paymentStatus` to a desired value.
- Continued delivery `PENDING` may require backend-log inspection for adapter errors/backoff or a disabled worker. A terminal `CONFLICT` needs binding investigation; more polling alone will not repair it.

### 17.6 Approval receipt expired during a paused A test

The receipt expires 900 seconds after M3 acceptance. Repeating the old ADMIN approval returns its recorded decision; it does **not** extend that receipt. A fresh quote alone also cannot extend it.

For the still-HIGH payment, an expired approval can cause a new confirmation to return `202 UNDER_REVIEW` rather than A9's expected `200 PROCESSING`. Treat that as a new review, not a pass. A rare `409 APPROVAL_EXPIRED` during posting rolls the transaction back and also is not posting success.

If you already received a new `202` from A9:

1. Keep `paymentId` and preserve the original A3/A10 `confirmKey`, `originalQuoteId`, and `firstConfirmData`.
2. Send A4 to capture the **current new** case/reference. Confirm `reviewable=true` and `REVIEW_REQUIRED`.
3. Approve that new case with A5; poll A6 for acknowledgment, then A7 for DRAFT.
4. Send A8 for the next fresh quote. Clear the now-used `reconfirmKey` once; it belongs to the previous 202 attempt and cannot be reused with this changed quote.
5. Send A9 promptly. Only `200 PROCESSING` is a pass. It captures the successful `processingConfirmData` for A11. Then perform A10/A11 and the ledger checks.

If no new review exists yet and SQL confirms the old receipt expired, do not approve the old case again. Keep the payment ID and original A3/A10 pair. Use section 17.4's temporary request to obtain a live quote for the actual DRAFT/QUOTED payment and save it as `freshQuoteId`. Clear the previously used `reconfirmKey` once and send A9 as an **intentional review-renewal attempt**. The still-HIGH facts should produce `202 UNDER_REVIEW`; its normal A9 success assertion will be red, which must not be recorded as a pass. Follow steps 1–5 above for the newly bound case and complete the later A9 with `200 PROCESSING`. Never use a folder-05 payment or a direct diagnostic case as a substitute for this binding.

### 17.7 Risk differs from the folder-05 expectation

Read passport `data.reasons` first (`risk_reasons` is the SQL storage column, not the response field). If the intended first payment already has `RECIPIENT_TODAY`, another same-day attempt to that recipient exists. The engine may be correct and the test precondition contaminated. `ALL_ATTEMPTS` includes other drafts and failed/canceled attempts; clearing variables does not erase history. Create one fresh recipient with the helper, reset the ten folder-05 values, and complete steps 1–4 before creating payment 2. Keep the pair on the same `Asia/Kolkata` calendar day.

If R1, R4, R5, or R6 unexpectedly appears, check actual stored KYC, amount/currency, purpose, and recipient snapshot. Do not weaken rules, edit returned reasons, delete history, or change expected LOW to MEDIUM just to make tests green.

### 17.8 Remaining errors and safe next checks

| Symptom | Meaning and action |
| --- | --- |
| `404 CASE_NOT_FOUND` on passport | No assessment exists for that payment, or the USER token does not own it. ADMIN may inspect an existing case. |
| `409 ASSESSMENT_CONFLICT` | The same assessment identity was used with different input. Deliberate folder-04 changed replay expects this; exact replay must preserve every request field. |
| `409 STALE_ASSESSMENT` | Current facts/fingerprint changed or the submitted sequence is no longer next. Read fresh context and use a new assessment UUID for a new assessment. |
| `409 CASE_CONFLICT` | Case is unbound, already decided, or the attempted decision contradicts stored state. Use a new active review for an alternative decision. |
| `ORA-20702` or another fixture-exists guard | This insert attempt stopped. Inspect expected fixture IDs/emails and reuse only a verified complete fixture; do not drop tables or rerun funding blindly. |
| Insufficient funds on confirmation | Synthetic spend has consumed available balance, or the wrong wallet was selected. Inspect wallet and ledger evidence. Use a reviewed funding/setup workflow; do not edit balances directly. |
| Folder 02 says Runner data is missing | Its scripts still use `pm.iterationData.get`. Apply all six manual-mode substitutions in section 7.2 or supply the proper data file for only that folder. |
| `503 POLICY_CORPUS_UNRECONCILED` | Fresh/current policy rows need canonical hashes. Run ADMIN folder 01 request 2 and repeat for zero updates, then retry the policy-list check. This can occur before folder 00 passes on a freshly migrated database. |
| `409 DUPLICATE_POLICY` | Same normalized content already exists. Inspect `fieldErrors.existingPolicyDocumentId`; do not index a stale `policyId` left by another request. |
| `409 CANONICAL_HASH_COLLISION` / `CANONICAL_HASH_MISMATCH` | Reconciliation detected incompatible evidence. Stop and investigate the corpus; do not overwrite stored hashes manually. |
| `503 FX_*` | The external Frankfurter quote provider is unavailable; retry after network/provider recovery. |
| `503 EMBEDDING_UNAVAILABLE` or timeout | Ollama/model is unavailable or cold. Warm `/api/embed`, verify model/dimensions, then retry. |
| Relevant vector query has no sources | Check all 13 per-policy index results, active generations, matching backend embedding space, and question relevance. Do not raise the distance threshold just to pass. |
| Test script says `Cannot read properties of undefined` | Often a secondary failure: a previous non-success response had no `data`. Fix its actual HTTP error first, not the follow-on assertion. |
| Historical `ORA-18716` masked by a 401 | The current integration includes legacy Oracle timestamp mapping. If a fresh signed GET passes but another endpoint fails, use its correlation ID and backend error; do not assume every 401 is JWT expiry or bypass security. |

## 18. Automated regression checks

**Purpose:** Postman demonstrates live user flows; automated tests cover transaction rollback, rule boundaries, replay, and timestamp cases that are difficult to exhaust manually. Run from the repository root with Java 17. These commands are separate evidence, not a substitute for the manual A/B and vector acceptance checks.

Start with the focused integration selection:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
.\mvnw.cmd -B -ntp -f backend/pom.xml '-Dtest=M5BackendApiTest,M5M3IntegrationConfigurationTest,M3M5TransactionIntegrationTest,M3ConfirmationBoundaryTest,M3ReviewReceiptTest' test
```

This five-class selection contains 28 tests in the reviewed checkout. Require zero failures/errors; inspect skips explicitly. A missing dependency requires network access, not deleting tests. Reports are in `backend/target/surefire-reports`.

For real Oracle opt-in tests, first load private process settings with section 3.2's `.env` loader in this terminal. Do not print them. The timestamp regression is especially relevant to the immediate 410 error:

```powershell
$env:M5_ORACLE_TIMESTAMP_TESTS = 'true'
try {
  .\mvnw.cmd -B -ntp -f backend/pom.xml '-Dtest=M5OracleTimestampRoundTripTest' test
} finally {
  Remove-Item Env:M5_ORACLE_TIMESTAMP_TESTS -ErrorAction SilentlyContinue
}
```

Expect four enabled tests, no failures/errors/skips. They use SELECTs and connection-local session settings, not table changes. Without the opt-in environment variable they are skipped, which is not a live Oracle pass.

Optional signed HTTP boot check against the real schema, without enabling its optional reconciliation mutation:

```powershell
$env:M5_ORACLE_BACKEND_TESTS = 'true'
Remove-Item Env:M5_ORACLE_RECONCILE_HASHES -ErrorAction SilentlyContinue
try {
  .\mvnw.cmd -B -ntp -f backend/pom.xml '-Dtest=M5OracleBackendAcceptanceTest' test
} finally {
  Remove-Item Env:M5_ORACLE_BACKEND_TESTS -ErrorAction SilentlyContinue
}
```

For native Oracle vector persistence/search acceptance, after policy hash reconciliation:

```powershell
$env:M5_ORACLE_POLICY_TESTS = 'true'
$env:M5_ORACLE_POLICY_ID = '00000000-0000-0000-0000-000000006e01'
try {
  .\mvnw.cmd -B -ntp -f backend/pom.xml '-Dtest=M5PolicyRepositoryOracleTest' test
} finally {
  Remove-Item Env:M5_ORACLE_POLICY_TESTS -ErrorAction SilentlyContinue
  Remove-Item Env:M5_ORACLE_POLICY_ID -ErrorAction SilentlyContinue
}
```

These opt-in tests do not migrate or seed fixtures. The policy repository test rolls back its temporary generation data. Live Ollama behavior still needs folders 01–03.

If claiming all regression tests pass, run the full backend suite separately and report the actual totals:

```powershell
.\mvnw.cmd -B -ntp -f backend/pom.xml test
```

Do not suppress unrelated failures or remove tests to turn a focused acceptance result into an all-suite claim.

## 19. Recorded evidence and final sign-off

### 19.1 What the earlier troubleshooting established

The following is historical evidence from the September 14 sessions, not a fresh execution performed while writing this guide:

| Area | Recorded evidence / remaining limitation |
| --- | --- |
| Folder 00 | User run showed **8/8** tests passing: `200`, `200`, `401`, `403` |
| Folder 04 | User run showed **12/12** tests passing, including replay/conflict and unbound-case protection |
| Folder 05 | Real HTTP verification produced first-payment `LOW/APPROVE/PROCEED/PROCESSING` and second-payment `MEDIUM/REVIEW/REVIEW_REQUIRED/UNDER_REVIEW`; stale history explained the earlier incorrect test setup |
| Vector retrieval | Real HTTP checks observed relevant cited sources with `mock=false` and the unrelated empty-source no-answer response; retain fresh per-policy indexing evidence for all 13 revisions |
| Folder A | Approval, acknowledgment, DRAFT, and fresh quoting were observed. The paused receipt subsequently expired; final A9 `200 PROCESSING` and the full replay proof were **not established** by those failed runs |
| B, C, D | A complete saved acceptance record for every manual field/side-effect check was not established here; execute and record the relevant sections |
| Focused automated checks | **32 tests passed** across the five integration classes plus four Oracle timestamp tests after the UTC correction |
| Broader test run | The recorded **187-test** run had **5 failures and 6 skips**. It was not all green. Failures referenced missing older solo/mock classes in `M5FixtureReaderUnitTest`, `M5RecordingSinkContractTest`, and `M5SoloSafetyUnitTest` |

Do not turn this history into a blanket “Member 5 fully passed” statement. Current acceptance requires the checklist below on the current checkout and environment. A completed documentation task does not itself rerun or certify the application.

### 19.2 Final acceptance checklist

- [ ] Database target and current migrations verified; synthetic upstream fixture verified without overwriting existing data.
- [ ] Folder 00: all four authorization outcomes and eight tests pass with the correct active environment.
- [ ] Folder 01: real finite nonzero 768-dimensional vector, stable hash reconciliation, and all 13 expected current IDs.
- [ ] Folder 02: all 13 exact ID/title pairs verified; each has real chunks, an active generation/space, and `mock=false`; unchanged indexing replay verified.
- [ ] Folder 03: relevant current-policy citations and explicit unrelated no-answer, both `mock=false`.
- [ ] Folder 04: R1 HIGH evidence, exact stored-data replay, changed replay conflict, and rejection of direct unbound approval.
- [ ] Folder 05: fresh recipient; first only R2 LOW/PROCESSING; second R2/R3/R5 MEDIUM/UNDER_REVIEW; all 16 tests pass.
- [ ] A: HIGH R4/R6 hold, durable ADMIN approval, actual acknowledgment, DRAFT, fresh quote/key, and `200 PROCESSING` within receipt validity.
- [ ] A10/A11: both historical response-data replays match their original pairs; current payment remains PROCESSING.
- [ ] SQL: A's journal is balanced and entry/event counts do not increase on replay.
- [ ] B: separate undecided case rejected and acknowledged; payment REJECTED; zero payment posting journal/initiated event.
- [ ] C: operator list/detail and contextual/non-contextual search fields inspected, not only generic status assertions.
- [ ] D: fresh immutable publication or explicitly labelled duplicate check, real indexing, and replay verified in an intended mutable test corpus; if skipped, disclose that publication remains untested.
- [ ] Focused regressions and relevant live Oracle opt-ins rerun; failures/skips recorded separately from passing selections.
- [ ] No claim of Kafka delivery, payout completion, settlement, refunds, live sanctions screening, or production compliance based on these tests.
- [ ] Evidence/export contains no ADMIN/USER JWT, JWT signing secret, or Oracle password. Indexing toggle restored to `false` after folder 02.

Use a simple evidence row for each section/policy/payment:

| Date/time + time zone | Folder/request | Input policy/payment/case ID | Expected status and fields | Actual result | Correlation ID | Pass / fail / not run |
| --- | --- | --- | --- | --- | --- | --- |
| Fill during testing | | | | | | |

Save the A scenario's IDs and replay evidence before resetting variables for B. Screenshots should show response/test evidence, not Authorization headers or private environment values. Report any unrun section explicitly; do not count it as passed.

### 19.3 Where each supporting file fits

This guide is the order to follow. The other files are inputs or technical references, not competing walkthroughs:

| File | Use |
| --- | --- |
| [Postman collection](postman/M5-Backend.postman_collection.json) | Import once; 47 request templates with scripts |
| [Current policy IDs](postman/m5-current-policy-ids.json) | Exact 13-policy identity checklist or folder-02 Runner data |
| [Backend launcher](scripts/Start-M5Backend.ps1) | Start the integrated local service with private settings |
| [Local JWT helper](scripts/New-M5TestToken.ps1) | Generate one-hour synthetic-account tokens; local testing only |
| [Base fixture](sql/m5-backend-test-data.sql) | One-time upstream users, KYC, wallets, recipients, balanced funding |
| [Direct R1 fixture](sql/m5-risk-demo-fixture.sql) | Pending-KYC stored payment observation for folder 04 |
| [Fresh folder-05 recipient](sql/m5-folder05-fresh-recipient.sql) | Additive clean-history recovery for a new LOW/MEDIUM pair |
| [Oracle verification](m5-oracle-verification.sql) | Read-only database inspection |
| [M5–M3 integration handoff](M5-M3-INTEGRATION-HANDOFF.md) | Technical explanation for the collaborating member, not a prerequisite to clicking requests |

Normal reruns do not require the historical database-reset guide or reset SQL. Preserve existing evidence and use the scoped recovery procedure for the actual failed step.

## 20. Detailed endpoint reference — all 47 saved requests

This section is the explanation behind each click, not a second collection to run. Repeated URLs are listed separately because authentication, inputs, state, and expected outcomes differ. For example, confirming a LOW payment, creating a HIGH review, and replaying an approved payment test different guarantees even though they use the same route.

Read the matching folder while following the earlier procedure:

| Folder reference | Main question answered |
| --- | --- |
| [00 — Authorization](#201-folder-00--can-the-right-identity-reach-the-right-api) | Can valid users enter, and are invalid identities/roles blocked? |
| [01 — Readiness](#202-folder-01--are-the-provider-and-current-corpus-ready) | Are real embeddings and the current policy corpus ready? |
| [02 — Indexing](#203-folder-02--can-each-current-policy-be-indexed-safely) | Does each intended document become a real immutable vector generation? |
| [03 — Search](#204-folder-03--does-search-return-grounded-evidence-and-abstain) | Does relevant search work, and does irrelevant search abstain? |
| [04 — Direct assessment](#205-folder-04--is-risk-authoritative-and-evidence-only-assessment-safe) | Are risk facts server-owned, and can raw evidence be kept separate from payment authority? |
| [05 — LOW/MEDIUM](#206-folder-05--do-isolated-rules-produce-the-correct-risk-level) | Do different authoritative facts produce the intended aggregate risk? |
| [A — Approval](#207-folder-a--does-an-approved-review-resume-payment-safely) | Does approval cross the member boundary and require safe reconfirmation? |
| [B — Rejection](#208-folder-b--does-rejection-stop-a-separate-payment) | Does an acknowledged rejection stop a held payment without posting? |
| [C — Operator reads](#209-folder-c--can-an-operator-inspect-cases-policies-and-context) | Can operators inspect the correct evidence without changing it? |
| [D — Policy writes](#2010-folder-d--does-policy-publication-preserve-identity-and-index-history) | Can policies be published, deduplicated, and indexed without corrupting history? |

Conventions used below:

- **ADMIN** means Authorization → Bearer Token → `{{adminToken}}`; **USER** means `{{userToken}}`. **No Auth** means no bearer header. Do not send either JWT to Ollama.
- Use `{{baseUrl}}` for backend URLs. Every supplied JSON body needs `Content-Type: application/json`; no-body requests should not be given an invented body.
- Successful backend responses have `correlationId` and `data`. Error responses instead expose top-level `code`, `message`, `fieldErrors`, and `ts`. Ollama's response is not wrapped in the backend envelope.
- “Saved” means a supplied Postman script writes collection variables. Environment values with those names can shadow the saved values; see section 5. A failed request must not be treated as having produced a fresh ID.
- The stated status is the result expected for **this test scenario**, not every possible valid use of the endpoint. Network failures, stale inputs, missing records, and authentication errors are separate outcomes.

The most important response-field distinctions are:

| Field | Meaning; what to inspect |
| --- | --- |
| Payment `data.status` | M3's payment lifecycle: DRAFT, QUOTED, UNDER_REVIEW, PROCESSING, etc. |
| Case `data.status` | M5's case lifecycle; do not equate it to payment status |
| Case `data.risk` | Aggregate LOW/MEDIUM/HIGH derived from stored facts |
| Case `data.reasons[]` | Individual rule evidence; each has `rule`, `code`, `severity`, and `description` |
| `screeningVerdict` | Original deterministic screening result; retained after an officer decision |
| `verdict` | Current case outcome, which can reflect a later manual approval/rejection |
| `paymentDisposition`, `reviewReference`, `reviewable` | Whether this evidence is bound to an active payment review and can be decided |
| `decisionId`, `deliveryState` | Durable decision identity and its delivery to M3; APPROVED alone is not acknowledgment |
| Index `generationId` / document `activeGenerationId` | A particular immutable set of chunks versus the pointer selecting the active set |
| `embeddingSpaceId` | Compatibility identity for configured embedding settings; query and document vectors must match |
| `mock=false` | Configured real mode, not sufficient alone to prove meaningful search or successful provider work on a cached replay |

### 20.1 Folder 00 — can the right identity reach the right API?

This folder isolates security before payment or vector behavior. It contains two positive and two deliberately negative tests. It does not attempt to prove every cross-owner access rule.

#### 00.1 — ADMIN token can list one policy

**Send:** ADMIN `GET {{baseUrl}}/api/policies?page=0&size=1`; no body or idempotency header. `page=0` selects the first page; `size=1` makes this a small readiness check.

**Backend / why:** validates the signed identity and ADMIN role, checks corpus readiness, and reads a policy page. This proves the administrator can enter an M5-protected API without involving embeddings or payment mutation.

**Expect:** `200`; nonempty `correlationId`; `data.content` is an array. Page metadata is `page`, `size`, `totalElements`, `totalPages`. This folder checks access, not that every policy is indexed.

**State / next:** read-only; saves no policy ID. If a fresh schema returns `503 POLICY_CORPUS_UNRECONCILED`, bootstrap with 01.2 and return here. Unexpected `401`/`403` must be resolved before ADMIN tests.

#### 00.2 — USER token can list owned payments

**Send:** USER `GET {{baseUrl}}/api/payments?page=0&size=1`; no body.

**Backend / why:** resolves the authenticated sender and returns its owner-scoped payment page. A valid USER must reach payment APIs while remaining unable to administer policies.

**Expect:** `200`, correlation envelope, `data.items` array with `page`, `size`, and `total`. An empty `items` array is valid before creating payments. Unlike the policy page, the array is named **`items`**, not `content`.

**State / next:** no payment is created and no IDs are saved. A successful call validates this USER token; it does not refresh the ADMIN token or demonstrate that another user's records are inaccessible.

#### 00.3 — Unsigned policy request is rejected

**Send:** **No Auth** `GET {{baseUrl}}/api/policies?page=0&size=1`; no body. Keep this request unsigned even when collection-level authentication exists.

**Backend / why:** rejects the request before protected policy access because it lacks a valid signed identity. This checks that a publicly reachable HTTP service does not imply publicly readable compliance data.

**Expect:** `401`, `code=AUTH_REQUIRED`, and a structured error response. Its tests should be green for that failure status. `200` would be a security failure, not an improvement.

**State / next:** no policy read result or mutation is produced; saves nothing. Do not “fix” this negative test by attaching a token.

#### 00.4 — USER cannot administer policies

**Send:** USER `GET {{baseUrl}}/api/policies?page=0&size=1`; no body. Use the same valid USER identity proven by 00.2.

**Backend / why:** accepts the identity but rejects its role. This distinguishes authorization from authentication: signing a token correctly must not grant ADMIN privileges.

**Expect:** `403` and `code=FORBIDDEN`. `401` is not the intended pass; it means the USER token was not successfully authenticated, so the role boundary has not yet been tested.

**State / next:** no protected policy data or mutations; saves nothing. Once all four checks pass, continue with readiness.

### 20.2 Folder 01 — are the provider and current corpus ready?

This folder separates embedding availability, policy identity readiness, and document inventory. These are independent prerequisites: one working vector does not mean Oracle policies are indexed.

#### 01.1 — Warm Ollama and verify the 768-dimensional embedding

**Send:** No Auth `POST {{ollamaUrl}}/api/embed`, JSON:

```json
{"model":"{{embeddingModel}}","input":"search_query: sender verification","truncate":false}
```

**Backend / why:** this calls Ollama directly, not the M5 controller. It loads/warms the configured model and obtains a real vector. The 768-value contract must match Oracle's vector width.

**Expect:** `200`; `embeddings[0]` contains exactly 768 finite numeric values with at least one nonzero value. It uses Ollama's native response, so do not look for `data` or backend `correlationId`.

**State / next:** no policy chunks or risk assessments are stored. A cold model may need warming before backend deadlines can be met. Postman's `embeddingModel` does not change backend model configuration.

#### 01.2 — Reconcile canonical hashes — safe to repeat

**Send:** ADMIN `POST {{baseUrl}}/api/policies/reconcile-hashes`; no body or idempotency header.

**Backend / why:** checks canonical content identities and fills missing hash metadata. Fresh V708 policies require this before normal policy operations. This prevents unrecognized duplicate policy content from being silently published.

**Expect:** `200`, correlation envelope, integer `data.updatedDocuments >= 0`. Send it again: expect zero updates. The saved assertion only checks nonnegative count; inspect the second zero manually.

**State / next:** this is a controlled database metadata mutation, not a pure read. It does not create vectors or revise document text. Collision/mismatch conflicts require investigation, not direct hash editing. Then continue with the inventory, or return to 00 if this was the fresh-schema bootstrap.

#### 01.3 — Verify all 13 V708 current policies exist

**Send:** ADMIN `GET {{baseUrl}}/api/policies?page=0&size=100`; no body.

**Backend / why:** lists document records after hash readiness. The script checks the 13 exact current UUIDs, so you cannot accidentally test only older retained policy text.

**Expect:** `200`, `data.content` containing all IDs in section 7.2, plus pagination metadata. The total is not required to be 13: superseded history and deliberately created policies may also be listed.

**State / next:** read-only; does not select or index all those policies automatically. Record inventory evidence, then index each current ID in folder 02. A listed document can still be `UNINDEXED`.

### 20.3 Folder 02 — can each current policy be indexed safely?

This folder links one verified document identity to one persisted vector generation. Repeat these two requests for every row in section 7.2. For manual Send, first make the six script substitutions there; simply setting environment values is insufficient for the original Runner-only scripts.

#### 02.1 — Verify Runner policy ID and title

**Send:** ADMIN `GET {{baseUrl}}/api/policies/{{currentPolicyId}}`; no body. Set `currentPolicyId` and matching `expectedPolicyTitle` from the 13-row table, and explicitly enable `allowPolicyIndexing=true`.

**Backend / why:** reads that immutable document. Its ID and title must match before you index it; otherwise a technically successful index call could index the wrong policy and make later retrieval misleading.

**Expect:** `200`; `data.id` matches the chosen UUID and `data.title` matches exactly. Detail also contains `content`, `category`, `documentHash`, `version`, `indexState`, `activeGenerationId`, `embeddingSpaceId`, `chunkerVersion`, and `chunkCount`. It can legitimately be UNINDEXED at this stage.

**State / next:** no database mutation and no automatic ID capture. Stop on an identity mismatch. The following POST checks the toggle/allowlist but does not prove this GET passed; you must check both assertions before proceeding.

#### 02.2 — Index verified current policy with real provider

**Send:** ADMIN `POST {{baseUrl}}/api/policies/{{currentPolicyId}}/index`; no body. Keep the same verified ID; do not send text, a vector, or a model override in this request.

**Backend / why:** reads stored content, chunks it, obtains configured embeddings, and publishes a complete active generation. An unchanged existing generation with the same embedding space and chunker configuration is reused. This tests the real document-to-Oracle path and safe retry behavior.

**Expect:** `200`; `data.policyDocumentId` matches, `mock=false`, positive `chunkCount`, nonempty `generationId`/`embeddingSpaceId`, plus `chunkerVersion`, `version`, and boolean `replayed`. Repeat unchanged indexing: expect replay with unchanged generation/count. A replay may not call the provider again.

**State / next:** publishes index data on new work; saves collection `generationId` and `embeddingSpaceId`. Record each policy's result because the next response overwrites those variables. Index responses do not contain `indexState`; GET detail again to inspect `INDEXED`/`activeGenerationId`. Disable the folder's indexing toggle after all 13 rows.

### 20.4 Folder 03 — does search return grounded evidence and abstain?

This folder tests search quality in both directions. A source-backed answer is useful only if unrelated queries do not return convincing but irrelevant policy text. Both requests use a null payment context to isolate retrieval from case lookup.

#### 03.1 — Relevant sender-verification question is grounded

**Send:** ADMIN `POST {{baseUrl}}/api/copilot/ask`, JSON:

```json
{"question":"When must a sender undergo manual review because their identity is unverified?","paymentId":null}
```

**Backend / why:** embeds the question, searches eligible current active Oracle generations in the matching space, applies the configured cutoff, and returns selected policy excerpts. This exercises the query side of the real vector feature, not only its indexing side.

**Expect:** `200`; `data.mock=false`, nonempty `answer`, nonempty `sources`, and `caseContext={}`. Each source exposes `policyDocumentId`, `title`, `chunkNumber`, and `excerpt`. Check source relevance and current IDs manually; no distance or raw vector is exposed. Several sources can be chunks of the same policy.

**State / next:** no policy/risk mutation and no saved runtime IDs. The current answer is the first selected excerpt, not a separate LLM-generated risk decision. A relevant no-answer needs indexing/space/readiness investigation before moving on.

#### 03.2 — Unrelated question returns explicit no-answer

**Send:** ADMIN `POST {{baseUrl}}/api/copilot/ask`, JSON:

```json
{"question":"How do I bake a chocolate cake?","paymentId":null}
```

**Backend / why:** performs the same real embedding/search path, but no compliance source should meet the relevance requirement. It tests abstention instead of inventing an answer or citing an unrelated policy.

**Expect:** `200`, `mock=false`, `sources=[]`, empty `caseContext`, and the exact answer `No grounded answer found in current policies.` No sources is the intended success here, not a missing-document error.

**State / next:** read-only; saves nothing. Do not change the cutoff to make either semantic test pass. Blank/oversized questions are different validation cases; this valid but irrelevant question should not return `400` or `404`.

### 20.5 Folder 04 — is risk authoritative and evidence-only assessment safe?

Use the separate pending-KYC SQL fixture and ADMIN token. This folder tests direct assessment without M3 confirmation. It must produce risk evidence, but that evidence must not gain the authority to release or debit a payment.

#### 04.1 — Read server-owned assessment context

**Send:** ADMIN `GET {{baseUrl}}/api/compliance/payments/{{riskFixturePaymentId}}/assessment-context`; no body. The input is the SQL fixture's payment UUID, not an A/B or folder-05 variable.

**Backend / why:** reads authoritative payment observations and computes a fingerprint plus the next assessment sequence. This prevents callers from constructing their own KYC, amount, recipient, and history facts for screening.

**Expect:** `200`; `data.paymentId` matches; `expectedPaymentFingerprint` is a 64-character SHA-256 string; `nextAssessmentSequence` is positive; `observedAt` identifies observation time. This endpoint returns metadata, not the full raw KYC/history snapshot.

**State / next:** read-only. Saves collection `expectedPaymentFingerprint` and `assessmentSequence`. The sequence is a suggestion, not a reservation; a concurrent assessment may make it stale. Use it promptly and never hardcode 1.

#### 04.2 — Create server-derived R1 assessment

**Send:** ADMIN `POST {{baseUrl}}/api/compliance/assess/{{riskFixturePaymentId}}`, with the saved JSON template:

```text
{
  "assessmentId": "{{directAssessmentId}}",
  "assessmentSequence": {{assessmentSequence}},
  "expectedPaymentFingerprint": "{{expectedPaymentFingerprint}}"
}
```

**Backend / why:** reads the facts again, verifies identity/sequence/fingerprint, evaluates rules, and stores assessment evidence and its case. The supplied fixture produces R1 HIGH `KYC_UNVERIFIED`, R2 MEDIUM `FIRST_TO_RECIPIENT`, and R4 MEDIUM `HIGH_VALUE`.

**Expect:** `201`; `data.risk=HIGH`, `screeningVerdict=REVIEW`, expected `reasons`, payment/assessment/case IDs, `assessedAt`, `ruleVersion`, and `ruleConfigHash`. The raw assessment response has no payment disposition or delivery state; read the case for those.

**State / next:** generates `directAssessmentId` only if empty; saves `directCaseId` and `directAssessmentData`. Stores evidence but does not bind a payment hold or post money. Assessment ID/body provide replay identity—there is no payment-style Idempotency-Key header. Do not submit risk/reasons; unknown extra JSON fields are ignored, not trusted overrides.

#### 04.3 — Exact assessment replay returns identical result

**Send:** ADMIN `POST {{baseUrl}}/api/compliance/assess/{{riskFixturePaymentId}}` and **exactly the same body values** as 04.2:

```text
{"assessmentId":"{{directAssessmentId}}","assessmentSequence":{{assessmentSequence}},"expectedPaymentFingerprint":"{{expectedPaymentFingerprint}}"}
```

Do not refresh context, increment the sequence, or regenerate the assessment ID between these two sends.

**Backend / why:** recognizes the original assessment identity and returns its immutable saved result. This tests stable retry behavior independently of how the database or clock may have changed afterward.

**Expect:** `200`, not `201`; response `data` deeply equals the saved `directAssessmentData`, including original IDs/evidence. The envelope's `correlationId` may differ because it identifies this HTTP request.

**State / next:** no second case or assessment is created; original captures remain unchanged. If the body changed, this is no longer an exact-replay test.

#### 04.4 — Changed request with same assessment ID conflicts

**Send:** ADMIN `POST {{baseUrl}}/api/compliance/assess/{{riskFixturePaymentId}}`, retaining assessment ID/sequence but changing only the fingerprint:

```text
{"assessmentId":"{{directAssessmentId}}","assessmentSequence":{{assessmentSequence}},"expectedPaymentFingerprint":"{{conflictingFingerprint}}"}
```

The saved pre-request script changes one hex character into local `conflictingFingerprint`; it preserves the original collection fingerprint.

**Backend / why:** detects that an already-used assessment identity is being presented with different input. It must not silently replace the original evidence under the same ID.

**Expect:** `409`, `code=ASSESSMENT_CONFLICT`, correlation ID, and structured `fieldErrors`. This deliberate conflict should pass its tests. A `200`/`201` would invalidate the replay-identity guarantee.

**State / next:** no replacement assessment is stored. Do not fix this request by issuing a new ID; that would remove the condition the negative test is meant to exercise.

#### 04.5 — Inspect evidence-only direct case

**Send:** ADMIN `GET {{baseUrl}}/api/compliance/cases/{{directCaseId}}`; no body.

**Backend / why:** reads the persisted case created by 04.2. The key distinction is whether risk evidence is actually bound to an M3 review, not simply whether a case exists.

**Expect:** `200`, matching case/payment/assessment IDs, `risk=HIGH`, `reviewable=false`, `paymentDisposition=null`, and `reviewReference=null`. The untouched case can have `status=UNDER_REVIEW` and both verdict fields REVIEW even while the actual payment remains DRAFT. Decision metadata remains null.

**State / next:** read-only, no new captures. This response explains why the next approval must fail: no active payment review authorizes a decision handoff.

#### 04.6 — Direct case cannot be approved before M3 binding

**Send:** ADMIN `PUT {{baseUrl}}/api/compliance/cases/{{directCaseId}}/approve`, JSON:

```json
{"reason":"This must fail because direct assessment is not an M3 review hold."}
```

**Backend / why:** validates the case's decision eligibility and rejects the missing active M3 binding. ADMIN status alone must not let an officer act on arbitrary evidence as if it controlled a payment.

**Expect:** `409`, `code=CASE_CONFLICT`, structured error and passing negative-test assertions. Changing the reason or refreshing the token cannot turn this particular unbound case into a valid approval target.

**State / next:** no decision/delivery record or payment posting should be produced. Move to the integrated scenarios for a legitimately bound review; do not weaken the guard.

### 20.6 Folder 05 — do isolated rules produce the correct risk level?

All eight requests use USER. Start with one verified sender, its funded USD wallet, and one fresh safe India recipient. The first payment isolates R2; the second introduces R3 and R5. Complete the first four sends before creating the second draft, on the same configured calendar day.

Payment responses contain `id`, `sourceWalletId`, `recipientId`, decimal-string `sourceAmount`, `sourceCurrency`, `payoutCurrency`, `status`, `selectedQuoteId`, `createdAt`, and `legacy`. They do **not** include risk, case ID, wallet balance, or receipt expiry. Use the corresponding passport and SQL for that evidence.

#### 05.1 — Create first safe-recipient payment for R2

**Send:** USER `POST {{baseUrl}}/api/payments/draft`; header `Idempotency-Key: {{r2DraftKey}}`; JSON:

```json
{
  "sourceWalletId":"{{sourceWalletId}}",
  "recipientId":"{{safeRecipientId}}",
  "sourceAmount":"100.0000",
  "sourceCurrency":"USD",
  "payoutCurrency":"INR",
  "purpose":"FAMILY_SUPPORT",
  "preference":"CHEAPEST"
}
```

**Backend / why:** validates the sender, KYC, wallet and recipient eligibility, persists the draft and frozen recipient data, and stores the draft replay identity. The chosen amount, purpose, and country avoid R4/R5/R6; verified KYC avoids R1.

**Expect:** `201`, `data.status=DRAFT`, matching wallet/recipient/amount/currencies, `legacy=false`, and a new intended `data.id`. Inspect identity even if the assertions pass: an old draft key can replay old data.

**State / next:** generates `r2DraftKey` if empty and saves `r2PaymentId`. No assessment or payment debit occurs at draft creation. Leave the second draft uncreated until 05.4 finishes.

#### 05.2 — Obtain quote for R2-only payment

**Send:** USER `POST {{baseUrl}}/api/payments/{{r2PaymentId}}/quotes`; no body or idempotency header.

**Backend / why:** obtains or reuses a valid quote generation for that payment, allowing subsequent confirmation to use actual rates, fees, and a validity window. Quoting is not itself risk approval or posting.

**Expect:** `201`; `data.paymentId` matches; `recommendedQuoteId` matches an entry in `quotes`; exactly one recommendation among three routes. Inspect `serverTime` and `expiresAt`. Each quote exposes `id`, `route`, `marketRate`, `offeredRate`, `feeAmount`, `recipientAmount`, `estimatedMinutes`, and `recommended`; monetary/rate fields are strings. No fixed exchange rate should be asserted.

**State / next:** saves `r2QuoteId`, not its expiry or payment status. The actual payment is QUOTED after quoting. Send 05.3 promptly with this ID, not A's or the second payment's quote.

#### 05.3 — Confirm R2-only payment — LOW auto-approval

**Send:** USER `POST {{baseUrl}}/api/payments/{{r2PaymentId}}/confirm`; `Idempotency-Key: {{r2ConfirmKey}}`; body `{"quoteId":"{{r2QuoteId}}"}`.

**Backend / why:** computes risk from stored facts, validates the live quote and remaining payment checks, and takes the automatic-approval path. One MEDIUM reason must not be mistaken for aggregate MEDIUM risk.

**Expect:** `200`, matching payment ID, `status=PROCESSING`, `selectedQuoteId={{r2QuoteId}}`. `202` means the isolated LOW scenario did not produce the intended outcome; inspect reasons before assuming a code bug.

**State / next:** generates `r2ConfirmKey`; no response variables are captured by this request. Success stores risk/disposition evidence, a balanced payment journal, initiated-event intent, and replay response. PROCESSING is not completed payout. Read the passport next.

#### 05.4 — Inspect R2-only passport

**Send:** USER `GET {{baseUrl}}/api/compliance/payments/{{r2PaymentId}}/passport`; no body.

**Backend / why:** reads the latest stored evidence for this owned payment without reassessing it. This proves why confirmation proceeded instead of merely observing that it did.

**Expect:** `200`; `risk=LOW`, `screeningVerdict=APPROVE`, `verdict=APPROVE`, case `status=PROCESSING`, `paymentDisposition=PROCEED`, `reviewable=false`. The reason-code array must be exactly `['FIRST_TO_RECIPIENT']`, with `rule=R2`, `severity=MEDIUM`, and its human-readable `description`. Review/decision fields are null for this new automatic approval.

**State / next:** saves `r2CaseId`; no database mutation. Only after this proof passes, create the second payment. Unexpected R3 indicates contaminated recipient history, not a reason to change the expected result.

#### 05.5 — Create second same-day BUSINESS payment for R3 and R5

**Send:** USER `POST {{baseUrl}}/api/payments/draft`; `Idempotency-Key: {{r3r5DraftKey}}`; same wallet/recipient/amount/currencies as 05.1, but this JSON:

```json
{
  "sourceWalletId":"{{sourceWalletId}}",
  "recipientId":"{{safeRecipientId}}",
  "sourceAmount":"100.0000",
  "sourceCurrency":"USD",
  "payoutCurrency":"INR",
  "purpose":"BUSINESS",
  "preference":"CHEAPEST"
}
```

**Backend / why:** persists a second distinct attempt to the same recipient with a short stored purpose. The first payment provides same-day history; BUSINESS provides the current enum-length R5 trigger.

**Expect:** `201 DRAFT`, a different ID from `r2PaymentId`, same intended safe recipient/wallet/currencies/amount, and `legacy=false`. The saved success test checks DRAFT; manually verify these other inputs.

**State / next:** generates `r3r5DraftKey` and saves `r3r5PaymentId`. No debit yet. Do not replace `safeRecipientId` between the two payments or reuse `r2DraftKey`.

#### 05.6 — Obtain quote for R3 and R5 payment

**Send:** USER `POST {{baseUrl}}/api/payments/{{r3r5PaymentId}}/quotes`; no body or idempotency header.

**Backend / why:** produces the second payment's valid quote generation. Quote ownership/association matters: the first payment's selected route is not interchangeable with this payment's quote.

**Expect:** `201`; matching `data.paymentId`, nonempty recommended ID that appears among the three routes, and live expiry. Inspect the same rate/fee/recipient-amount/estimated-minutes fields as 05.2. The quote response has no `status` field.

**State / next:** saves `r3r5QuoteId`, not expiry/status. Quoting does not post money. Immediately send 05.7 using this payment's quote and confirmation key.

#### 05.7 — Confirm second payment — expected MEDIUM review

**Send:** USER `POST {{baseUrl}}/api/payments/{{r3r5PaymentId}}/confirm`; `Idempotency-Key: {{r3r5ConfirmKey}}`; body `{"quoteId":"{{r3r5QuoteId}}"}`.

**Backend / why:** evaluates the current facts and binds a review instead of posting because multiple MEDIUM reasons require review. The hold must be associated with a real payment and assessment, not only an unbound case.

**Expect:** `202`, matching payment ID, `status=UNDER_REVIEW`. On this new never-posted held payment, `selectedQuoteId` normally remains null; it is populated when posting succeeds, not merely when requesting a review.

**State / next:** generates `r3r5ConfirmKey`; no response captures. Stores assessment, review binding, review-event intent, and the replay outcome; no payment debit. The reason/case fields are in the next passport response, not this Payment response.

#### 05.8 — Inspect R2 plus R3 plus R5 passport

**Send:** USER `GET {{baseUrl}}/api/compliance/payments/{{r3r5PaymentId}}/passport`; no body.

**Backend / why:** reads the authoritative explanation and binding for the MEDIUM hold. This demonstrates aggregation and shows exactly which changed inputs caused review.

**Expect:** `200`; `risk=MEDIUM`, case `status=UNDER_REVIEW`, `screeningVerdict=REVIEW`, `verdict=REVIEW`, `paymentDisposition=REVIEW_REQUIRED`, `reviewable=true`, nonempty review reference. Inspect R2 `FIRST_TO_RECIPIENT`, R3 `RECIPIENT_TODAY`, and R5 `SHORT_PURPOSE`, each MEDIUM. The saved assertion checks inclusion; inspect unexpected extra reasons manually.

**State / next:** saves `r3r5CaseId`; read-only. R2 remains because the first payment is PROCESSING, not COMPLETED; R3 counts the earlier attempt; R5 measures the stored BUSINESS text. This is a real review, but keep its variables distinct from the separate HIGH approval test.

### 20.7 Folder A — does an approved review resume payment safely?

This is the integrated approval lifecycle, not a collection of independent requests. Use a new HIGH-risk fixture payment; stop at the delivery checkpoints. A5/A6 use ADMIN, all other A requests use USER. The successful end condition is PROCESSING plus balanced, nonduplicated posting evidence—not external payout completion.

#### A1 — Create synthetic owned draft

**Send:** USER `POST {{baseUrl}}/api/payments/draft`; `Idempotency-Key: {{draftKey}}`; JSON:

```json
{
  "sourceWalletId":"{{sourceWalletId}}",
  "recipientId":"{{recipientId}}",
  "sourceAmount":"{{sourceAmount}}",
  "sourceCurrency":"USD",
  "payoutCurrency":"INR",
  "purpose":"FAMILY_SUPPORT",
  "preference":"CHEAPEST"
}
```

**Backend / why:** validates and persists an owned draft with frozen recipient facts. Use source amount `1500.0000` and the synthetic RU recipient from section 10 so R4 and R6 are present when screening occurs.

**Expect:** `201 DRAFT`, correct payment/wallet/recipient/amount/currencies, and `legacy=false`. A success response does not prove a new row if the draft key was reused; compare the returned ID to the intended new scenario.

**State / next:** generates `draftKey` if empty and saves `paymentId`/`paymentStatus`. No screening or posting occurs yet. Preserve this scenario's IDs and keys through A11.

#### A2 — Obtain original quote

**Send:** USER `POST {{baseUrl}}/api/payments/{{paymentId}}/quotes`; no body or idempotency header.

**Backend / why:** produces or reuses a still-live three-route quote generation for an actual DRAFT/QUOTED payment. It establishes the original confirmation payload and the later historical replay pair.

**Expect:** `201`; correct `paymentId`; three `quotes`, exactly one `recommended=true`, and `recommendedQuoteId` identifying it. Read `recommendationReason`, `expiresAt`, `serverTime`, rates, fees, recipient amounts, and estimated minutes. Do not assert a fixed exchange rate or that a CHEAPEST preference must always pick the route literally named CHEAPEST: the preference compares recipient outcomes.

**State / next:** saves `originalQuoteId` and `originalQuoteExpiresAt`, but does not update cached `paymentStatus`. Actual payment becomes QUOTED. Once A3 succeeds, preserve this original quote ID for A10.

#### A3 — Confirm original quote — expected review fixture

**Send:** USER `POST {{baseUrl}}/api/payments/{{paymentId}}/confirm`; `Idempotency-Key: {{confirmKey}}`; body `{"quoteId":"{{originalQuoteId}}"}`. Send while the quote is live.

**Backend / why:** obtains authoritative M5 screening evidence, validates confirmation prerequisites, and binds the HIGH result to a payment hold. This is where the direct diagnostic case from folder 04 becomes meaningfully different from integrated review.

**Expect:** `202`, matching Payment `data.id`, and `status=UNDER_REVIEW`. A new held payment normally has `selectedQuoteId=null`. Risk and case ID are not included here; A4 obtains them.

**State / next:** generates `confirmKey`; saves `paymentStatus` and JSON-stringified `firstConfirmData`. Persists assessment, review binding/event intent, and the committed retry result without a payment debit. Keep this exact key/body/payment pairing for A10.

#### A4 — Owner passport — capture authoritative case

**Send:** USER `GET {{baseUrl}}/api/compliance/payments/{{paymentId}}/passport`; no body.

**Backend / why:** authorizes access to the owner's latest case evidence and exposes the review binding created by confirmation. The officer must decide this current bound case, not an arbitrary historical/direct case.

**Expect:** `200`; matching `paymentId`, nonempty case/assessment/reference IDs, `risk=HIGH`, `screeningVerdict=REVIEW`, `verdict=REVIEW`, case `status=UNDER_REVIEW`, `paymentDisposition=REVIEW_REQUIRED`, `reviewable=true`. Reasons include R6/HIGH `HIGH_RISK_DEST` and R4/MEDIUM `HIGH_VALUE`; other history reasons may coexist.

**State / next:** saves `caseId`, `assessmentId`, `reviewReference`, and `deliveryState`; does not update `paymentStatus`. Null delivery is normal before a decision. No mutation occurs. Use the captured case for A5.

#### A5 — ADMIN approve held case

**Send:** ADMIN `PUT {{baseUrl}}/api/compliance/cases/{{caseId}}/approve`; body `{"reason":"Synthetic evidence reviewed"}`; no idempotency header. Its local pre-request guard needs cached `paymentStatus=UNDER_REVIEW` from the actual confirmation/read.

**Backend / why:** checks that the case is active and reviewable, records the reviewer and reason, and persists a durable decision for asynchronous M3 delivery. Approval must be auditable and bound to this payment's evidence.

**Expect:** `200`; same case identity, `status=APPROVED`, `verdict=APPROVE`, `reviewable=false`, nonempty `decisionId`, `decidedBy`, `decidedAt`, and matching `decisionReason`. Delivery may be PENDING or already ACKNOWLEDGED. Original risk/reasons and `screeningVerdict=REVIEW` remain; the prior REVIEW_REQUIRED disposition/reference are not erased from this evidence.

**State / next:** saves `decisionId`/`deliveryState`. No payment is posted. Repeating the same case/action/normalized reason/reviewer can replay the stored decision; changing that decision identity conflicts. Repeat approval does not renew its receipt. Poll A6.

#### A6 — Inspect delivery — repeat manually until acknowledged

**Send:** ADMIN `GET {{baseUrl}}/api/compliance/cases/{{caseId}}`; no body. Repeat at sensible intervals while delivery is pending.

**Backend / why:** reads the decision's delivery result, separating “M5 saved an approval” from “M3 accepted the approval.” The background worker, not this GET, performs the handoff.

**Expect:** `200`; the same case/payment/decision, case still APPROVED, and eventually `deliveryState=ACKNOWLEDGED`. The saved tests can pass while delivery remains PENDING or CONFLICT; inspect that value yourself.

**State / next:** updates collection `deliveryState`; no new approval or database mutation from this GET. Acknowledgment means M3 recorded the decision/receipt. Continue to A7; do not skip this wait or treat terminal CONFLICT as a transient delay.

#### A7 — Owner payment — wait for DRAFT

**Send:** USER `GET {{baseUrl}}/api/payments/{{paymentId}}`; no body, after acknowledged delivery.

**Backend / why:** reads M3's actual payment lifecycle. The approval should reopen a confirmation opportunity; it should not silently transfer funds on the officer's click.

**Expect:** `200`, matching payment identity/amount/recipient, and `status=DRAFT`. Receipt expiry and approval IDs are not present in this Payment DTO; inspect the SQL evidence if expiry needs diagnosis.

**State / next:** saves the actual `paymentStatus`. This GET does not transition payment state. UNDER_REVIEW means the required result has not been observed; QUOTED may mean you already ran A8. Never manually label either state DRAFT just to pass the next guard.

#### A8 — Obtain fresh quote after M3 acceptance

**Send:** USER `POST {{baseUrl}}/api/payments/{{paymentId}}/quotes`; no body/key. Its local guard requires both `paymentStatus=DRAFT` and `deliveryState=ACKNOWLEDGED`.

**Backend / why:** approval invalidated the old quote generation, so a new confirmation must use fresh rate/fee/route data. The sender must see a current economic outcome before posting.

**Expect:** `201`; correct payment, live expiry, and `recommendedQuoteId` different from the original. Inspect the full quote fields as for A2. This response has no payment status.

**State / next:** saves `freshQuoteId` and `freshQuoteExpiresAt`; actual payment becomes QUOTED, but cached `paymentStatus` is not updated here. The 900-second approval receipt is not renewed. Send A9 promptly; use section 17.4 for the QUOTED-refresh guard trap if you pause.

#### A9 — Reconfirm with fresh key and quote

**Send:** USER `POST {{baseUrl}}/api/payments/{{paymentId}}/confirm`; `Idempotency-Key: {{reconfirmKey}}`; body `{"quoteId":"{{freshQuoteId}}"}`. The script generates this key if empty and rejects equality with `confirmKey`.

**Backend / why:** reassesses authoritative facts, validates the live quote, checks the matching unconsumed approval receipt and remaining KYC/recipient/funds rules, then posts through the normal ledger boundary. An approval is time-limited permission to attempt confirmation, not permission to bypass all payment controls.

**Expect:** exactly `200`, same payment ID, `status=PROCESSING`, and `selectedQuoteId` equal to `freshQuoteId`. `202` is a new review, not a pass.

**State / next:** on success, consumes the receipt and stores fresh assessment/disposition, balanced posting, initiated-event intent, and retry response. Saves `paymentStatus` and `processingConfirmData`; importantly, the script also fills those variables on an unexpected 202, so their presence alone proves nothing. The latest passport may now reference a newer assessment/case, not the earlier APPROVED case. Perform the SQL count checks and both replays.

#### A10 — Replay original key and original quote

**Send:** USER `POST {{baseUrl}}/api/payments/{{paymentId}}/confirm`; header `Idempotency-Key: {{confirmKey}}`; body `{"quoteId":"{{originalQuoteId}}"}`. Preserve the A3 payment/key/quote pairing exactly.

**Backend / why:** resolves an already-committed operation before applying current business-state/quote-expiry checks. A delayed retry should receive its original answer, not trigger a new assessment or change the payment.

**Expect:** historical `202 UNDER_REVIEW`, with `data` deeply equal to `firstConfirmData`. The actual payment remains PROCESSING after successful A9. A new correlation ID is normal.

**State / next:** no key generation or response captures, and no new hold/debit/event. A committed replay does not need its quote or receipt still live, but its JWT must remain valid. Continue with the successful-confirmation replay.

#### A11 — Replay reconfirmation key and fresh quote

**Send:** USER `POST {{baseUrl}}/api/payments/{{paymentId}}/confirm`; `Idempotency-Key: {{reconfirmKey}}`; body `{"quoteId":"{{freshQuoteId}}"}` from the successful A9.

**Backend / why:** returns the committed posting outcome even though its one-use receipt has already been consumed. This is retrying a completed operation, not authorizing a second new payment confirmation.

**Expect:** `200 PROCESSING`; `data` deeply equals the successful `processingConfirmData`, including selected quote/payment identity. Ensure that saved data came from A9's actual 200, not a failed 202 review-renewal attempt.

**State / next:** no new assessment, journal entries, initiated events, or captures. Compare SQL counts/balances before and after A10/A11. Response equality alone is not storage-level proof of no duplicate debit. Save A's evidence before resetting for B.

### 20.8 Folder B — does rejection stop a separate payment?

Prepare a new HIGH-risk payment with A1–A4 only. Do not approve it first. Rejection tests the other officer decision, using a distinct undecided case; it cannot be demonstrated by contradicting A's already-approved case.

#### B1 — ADMIN reject an undecided active case

**Send:** ADMIN `PUT {{baseUrl}}/api/compliance/cases/{{caseId}}/reject`; body `{"reason":"Synthetic evidence insufficient"}`; no idempotency header. The pre-request guard needs cached `paymentStatus=UNDER_REVIEW`.

**Backend / why:** checks active review eligibility and records a durable negative decision with reviewer and reason. Rejection requires a nonblank reason so the decision can be explained and audited.

**Expect:** `200`; case `status=REJECTED`, `verdict=BLOCK`, `reviewable=false`, decision/reviewer/time metadata, exact reason, and delivery PENDING or ACKNOWLEDGED. Original `screeningVerdict=REVIEW`, risk/reasons, disposition, and review reference remain evidence; this is an officer's BLOCK outcome, not a newly computed rule-engine BLOCK.

**State / next:** saves `decisionId`/`deliveryState`; queues handoff without posting a payment. A changed decision/reason/reviewer on a decided case can return CASE_CONFLICT. Poll B2.

#### B2 — Inspect rejection delivery — repeat until acknowledged

**Send:** ADMIN `GET {{baseUrl}}/api/compliance/cases/{{caseId}}`; no body.

**Backend / why:** observes whether M3 accepted the stored rejection. Reading the case must not create another rejection or make a pending decision appear delivered.

**Expect:** `200`; same case/payment/decision, case REJECTED, verdict BLOCK, and eventually `deliveryState=ACKNOWLEDGED`. The existing tests check the decided case but only warn about missing acknowledgment, so green tests while PENDING are not sufficient.

**State / next:** updates collection `deliveryState`; the GET is read-only. On acknowledgment, move to B3. Investigate a terminal delivery conflict instead of repeatedly issuing rejection commands.

#### B3 — Rejected payment has no posting path

**Send:** USER `GET {{baseUrl}}/api/payments/{{paymentId}}`; no body. The pre-request guard requires `deliveryState=ACKNOWLEDGED`.

**Backend / why:** reads the actual M3 result after the negative handoff. A rejected held payment must not proceed simply because an earlier quote or confirmation existed.

**Expect:** `200`, matching payment ID, `status=REJECTED`. For this new never-posted payment, `selectedQuoteId` normally remains null. Separately confirm zero payment-journal entries and zero `payment.initiated` events using section 16.

**State / next:** this request captures no variables, including no update to `paymentStatus`. Its named test checks status, not the ledger, despite the “no posting” title. Save both response and SQL evidence. No refund is expected because this scenario never posted the sender's payment.

### 20.9 Folder C — can an operator inspect cases, policies, and context?

All five requests use ADMIN. These are operator-facing reads, including the two read-only Copilot POSTs. Their supplied tests mostly check status and correlation, so manually inspect the domain fields below rather than treating two green assertions as full acceptance.

#### C1 — List reviewable cases

**Send:** ADMIN `GET {{baseUrl}}/api/compliance/cases?reviewable=true&page=0&size=20`; no body.

**Backend / why:** filters the case inventory to current actionable reviews. An operator needs this queue to distinguish active payment holds from historical/direct evidence and already-decided cases.

**Expect:** `200`; `data.items` array, `page`, `size`, `totalElements`. Inspect each returned item's case/payment IDs, risk/reasons, `reviewable=true`, nonempty binding reference, and REVIEW_REQUIRED disposition. An empty queue is valid if all reviews were decided; it is not an error.

**State / next:** read-only; no case is selected automatically. Do not assume a case still qualifies after someone else decides it. This saved request tests the reviewable filter; it does not exercise every possible combination of the endpoint's optional status/risk filters.

#### C2 — List policies

**Send:** ADMIN `GET {{baseUrl}}/api/policies?page=0&size=100`; no body.

**Backend / why:** exposes document inventory for operators to select and inspect. Unlike 01.3's exact current-ID assertion, this request's generic tests do not check that every required policy is present.

**Expect:** `200`; `data.content`, `page`, `size`, `totalElements`, `totalPages`. Inspect IDs/titles/categories and index metadata. Retained older documents can appear; use the known current IDs rather than assuming every listed row belongs to the current 13-policy set. The DTO does not expose a superseded-revision flag.

**State / next:** read-only; no automatic `policyId` capture. Choose an intended `data.content` item and set its UUID as collection `policyId` for C3. Remove an unintended environment override of that name.

#### C3 — Policy detail

**Send:** ADMIN `GET {{baseUrl}}/api/policies/{{policyId}}`; no body.

**Backend / why:** reads the exact document selected in C2 or created in D2. This supports traceability from an operator's reference to stored content and its active index generation.

**Expect:** `200`, matching `data.id`, intended `title`, `category`, and `content`. Inspect `documentHash`, `createdAt`, `version`, `indexState`, `activeGenerationId`, `embeddingSpaceId`, `chunkerVersion`, `chunkCount`. `documentHash` exposes the canonical content hash; it is not an embedding or a distance score. UNINDEXED is valid for a newly created, not-yet-indexed policy.

**State / next:** no mutation or captures. For an indexed-policy acceptance check, require INDEXED, positive chunks, and the expected active generation. A `404 POLICY_NOT_FOUND` means the selected ID did not resolve; do not silently substitute another document.

#### C4 — Copilot with stored payment context

**Send:** ADMIN `POST {{baseUrl}}/api/copilot/ask`; saved template:

```text
{"question": {{questionJson}}, "paymentId": "{{paymentId}}"}
```

Set `question` to the desired nonblank policy question and `paymentId` to an assessed payment. The pre-request script uses `JSON.stringify(question)` to make local `questionJson`; do not surround that template value with another pair of quotes.

**Backend / why:** obtains authorized stored case context, embeds the question, and retrieves grounded policy references. This lets an operator view rules alongside the selected payment's evidence without allowing search text to decide risk.

**Expect:** `200`; `answer`, `sources`, `mock=false`, and `caseContext` containing the intended `paymentId`/`caseId`, `risk`, `reasons`, case `status`, both verdicts, `paymentDisposition`, `reviewable`, and `suggestedAction`. Compare those fields to the passport. For an out-of-corpus question, the explicit no-answer can still include valid case context.

**State / next:** no reassessment/decision/posting and no persistent captures. Context is returned separately; it does not change the question embedding. Missing/unavailable case context is not repaired by guessing facts or submitting risk values.

#### C5 — Copilot without payment context

**Send:** ADMIN `POST {{baseUrl}}/api/copilot/ask`; saved template `{"question": {{questionJson}}, "paymentId": null}`. Keep the same intended question; the same pre-request serialization applies.

**Backend / why:** performs policy retrieval without a case lookup. This checks that general reference questions do not inherit the last payment's private context from a previous request.

**Expect:** `200`, `mock=false`, grounded `answer`/`sources` or the explicit empty-source no-answer, and **`caseContext={}`**. With the same question/corpus, do not require a different policy answer simply because context was removed: retrieval is question-based.

**State / next:** read-only; no case or payment is modified. Inspect the empty context manually because the generic saved tests do not establish that boundary. Keep folder 03's semantic pair as the stronger search acceptance check.

### 20.10 Folder D — does policy publication preserve identity and index history?

This folder deliberately writes policy metadata/content/index state. Run it last and only in an intended mutable test corpus. The folder-02 indexing toggle does not guard D. It tests publishing policies—not adding new executable risk rules.

#### D1 — Reconcile legacy canonical hashes

**Send:** ADMIN `POST {{baseUrl}}/api/policies/reconcile-hashes`; no body/key.

**Backend / why:** uses the same reconciliation operation as 01.2, now as an explicit maintenance/repeatability check. Normalized content identity must remain stable across repeated operator calls.

**Expect:** `200`; integer `data.updatedDocuments >= 0`, normally zero if folder 01 already completed. A second unchanged call must report zero. The generic D1 tests only check HTTP status and correlation; manually verify the count.

**State / next:** transactionally fills missing hashes after conflict checks. It does not edit policy text or generate vectors. A canonical collision/mismatch means stop and investigate, not retry until it disappears. Continue to D2 only if deliberate publication is intended.

#### D2 — Create immutable synthetic policy

**Send:** ADMIN `POST {{baseUrl}}/api/policies`; no idempotency header; saved template:

```text
{
  "title":"{{policyTitle}}",
  "category":"SUPPORT",
  "content":{{policyContentJson}}
}
```

The pre-request script serializes collection `policyContent` into local `policyContentJson`. Review the title/text first; leave this clearly synthetic. API validation requires a nonblank title up to 200 Unicode code points and nonempty content up to 64 KiB UTF-8 and 5,000 words. Supported categories are KYC, AML, PAYMENT_REVIEW, COUNTRY_RULE, and SUPPORT; the saved request uses SUPPORT.

**Backend / why:** normalizes content, validates it, checks canonical deduplication, and persists a new immutable document. This separates publishing reference text from the more expensive vector-generation operation.

**Expect:** for genuinely new content, `201`; matching title/category/content, generated `id`, `documentHash`, `createdAt`, `version=0`, `indexState=UNINDEXED`, zero chunks, and null active generation/space/chunker metadata. Identical normalized content instead returns `409 DUPLICATE_POLICY` with `fieldErrors.existingPolicyDocumentId`.

**State / next:** saves new `policyId` only on success. Repetition is content-deduplicated, not payment-key replay. Do not index a stale ID after failed creation; inspect/select the existing document deliberately for a duplicate test. The new row remains in the corpus; there is no cleanup endpoint supplied here.

#### D3 — Index selected policy with configured provider

**Send:** ADMIN `POST {{baseUrl}}/api/policies/{{policyId}}/index`; no body/key. Verify the resolved ID is D2's document or a deliberately selected existing document, not an environment-shadowed value.

**Backend / why:** chunks stored immutable content, uses the backend's configured document embedding model, persists a complete vector generation, and activates it. Repeating unchanged work should reuse the matching generation rather than duplicate chunks.

**Expect:** `200`; matching `policyDocumentId`, nonempty `generationId`, `embeddingSpaceId`, and `chunkerVersion`, positive `chunkCount`, `version`, `mock=false`, and boolean `replayed`. First publication generally reports false; already-indexed unchanged content can report true. Repeat unchanged: same generation/version/count and `replayed=true`. Use C3 to verify INDEXED because this response has no `indexState` field.

**State / next:** saves `generationId`/`embeddingSpaceId`; successful new indexing makes the document eligible for search and may change future rankings. D3's generic tests do not verify these vector fields; inspect them manually. It does not change R1–R6 enforcement. Restore the intended C policy selection and record the extra corpus document.

### 20.11 Reading a failure without losing the scenario

For every endpoint above, distinguish the expected negative test from an unexpected failure. The raw status alone is not the diagnosis:

| Observation | What to inspect next |
| --- | --- |
| No HTTP response | Pre-request script error, unresolved variable, or failed state guard before any send |
| `401` instead of the expected success/403 | Actual token and environment; preserve operation IDs/keys while refreshing authentication |
| `503 POLICY_CORPUS_UNRECONCILED` | Explicit ADMIN hash reconciliation, including the fresh-schema bootstrap before policy-list preflight |
| `409` on an intended exact replay | Its structured code and exact identity/body pairing; do not blindly regenerate IDs |
| `410` on confirmation | The selected quote, actual payment state, server time and expiry; follow section 17.4 |
| `202` at A9 | New review, not successful posting; snapshot variables can still have been populated |
| Undefined `data` in a test script | The original HTTP error above it; an error envelope does not contain normal success data |
| All generic tests green | Still inspect each endpoint's manual fields, delivery acknowledgment, source relevance, and required SQL side-effect evidence |

These descriptions are checked against the saved collection, controllers, services, and response DTOs. They define what a test should demonstrate; they do not add new endpoint implementations or claim those endpoints were re-executed while documenting them.
