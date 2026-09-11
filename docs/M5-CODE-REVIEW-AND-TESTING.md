# Member 5: code review and testing guide

Reviewed 2026-09-11 against the current folder, not a proposed implementation.

## Verdict and evidence

**Member 5 is not complete against the supplied implementation specification.** The current Java is the earlier drop-in prototype. Its API, lifecycle and storage differ materially from the revised specification; successful prototype requests do not establish specification acceptance.

Comparison references:

- [Revised Member 5 specification](C:/Users/Nitya/Downloads/05-member5-compliance-ai.md)
- [Earlier drop-in README](C:/Users/Nitya/Documents/fluxpay-member5-ollama-vector-2/README-M5-DROPIN.md)
- [Ollama adaptation README](C:/Users/Nitya/Documents/fluxpay-member5-ollama-vector-2/README-OLLAMA-VECTOR-SETUP.md)

The revised specification is the comparison baseline. The READMEs explain the current implementation and intentional adaptations; they do not override the revised acceptance criteria. In particular, JWT, delivery records and isolated fixtures are now solo requirements. Actual M3 transport/payment execution and shared frontend integration remain separate integration gates.

Fresh Maven result: **BUILD SUCCESS; Tests run: 0, Failures: 0, Errors: 0, Skipped: 0**. Maven completed the test lifecycle; no automated Java behavior tests ran. Only TestAuthHelper.java exists under the Java test source tree. The Python launcher tests are not Member 5 business tests.

No application Java, configuration, migrations or database data was changed during this review. The three files in this testing handoff are documentation/test-input artifacts. The Postman requests and SQL fixture script were not executed. The recreated database's current physical vector definition and live API behavior remain to be checked.

## 1. Coverage checklist

| Area | Required by revised specification | Current code | Verdict |
|---|---|---|---|
| Layering and envelopes | Horizontal layers; shared ApiResponse/ApiError shapes | Controller/service/repository layering and success envelope exist | Present; individual error mappings differ |
| Case storage | Extend screening_cases; head and decision tables; assessment/snapshot/version metadata | Separate compliance_cases table with basic fields | Not aligned |
| Case lifecycle | LOW → PROCESSING; MEDIUM/HIGH → UNDER_REVIEW; APPROVED/REJECTED decisions | LOW → CLOSED; MEDIUM/HIGH → OPEN | Not aligned |
| Assessment identity | assessmentId, sequence, payment/request fingerprints; immutable replay; fresh cycles | Only payment ID and optional hints; latest case returned unchanged | Missing cycle/replay protocol |
| Rule engine | Exact R1–R6 conditions, names, order and configuration | HIGH precedence and two-MEDIUM aggregation exist; most rules differ | Partial |
| Review decisions | Active disposition/reference; latest-case eligibility; authenticated reviewer; atomic durable record; idempotent delivery | OPEN check and direct case update | Partial; races and replay differ |
| Access control | ADMIN APIs; owner/admin passport; scoped real JWT security | All three M5 API prefixes permitAll | Missing |
| Passport and lists | Passport route; risk/reviewable/status filters; bounded pagination | No passport; status-only case list; unpaged arrays | Missing/partial |
| Policy creation | Normalized content-only SHA-256; size limits; duplicate ID field | Raw title + separator + content hash; title validation | Partial |
| Indexing | Versioned generations; publication lock; model identity; no-op replay; bounds/deadlines | Transactional delete/rebuild; provider calls inside transaction | Partial |
| Oracle vectors | Native storage and exact cosine retrieval | JDBC VECTOR_FLOAT32 binding and VECTOR_DISTANCE query | Implemented; live round-trip tests pending |
| Vector compatibility | Fixed width plus finite/nonzero validation and embedding-space isolation | Mock/Ollama default 768; API default 1536; no space metadata | Partial; configuration-dependent |
| Copilot | Eligible sources; distance threshold; exact no-answer; mock flag; authorized stored case context | Top-five excerpts; no threshold/mock flag; paymentId ignored | Partial |
| Error contract | Specific 400/404/409/503 codes and frozen envelope | Generic VALIDATION/NOT_FOUND/CONFLICT for handled exceptions | Partial |
| Solo acceptance | Isolated launcher, fixture reader/sink, signed tokens, nonzero unit/web/contract/Oracle tests and scripts | None of the specified M5 test infrastructure exists | Missing |
| M5 UI | Actual src/ts components and standalone fixture/live harness | Specified files absent; src/js scaffolds are not proof | Missing in existence check; no rendering tested |

### Important source locations

- Wrong current case states/storage: [enum](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/common/enums/ComplianceCaseStatus.java:3), [V601](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/resources/db/migration/V601__m5_compliance_cases.sql:5).
- Existing-case reuse, LOW auto-close and decision updates: [ComplianceCaseService](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/service/ComplianceCaseService.java:60).
- Prior-payment query: [ComplianceLookupRepository](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/repository/ComplianceLookupRepository.java:72).
- Rules: [ComplianceAssessmentService](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/service/ComplianceAssessmentService.java:65).
- Public routes: [SecurityConfig](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/common/security/SecurityConfig.java:33).
- Caller-supplied reviewer and optional reason: [decision request](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/dto/ComplianceDecisionRequest.java:6).
- Hashing: [PolicyDocumentService](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/service/PolicyDocumentService.java:31).
- Reindex transaction: [PolicyIndexingService](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/service/PolicyIndexingService.java:43).
- Native storage/search: [PolicyVectorRepository](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/repository/PolicyVectorRepository.java:35).
- Copilot eligibility/output: [CopilotService](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/java/com/fluxpay/service/CopilotService.java:56).
- Current policy schema: [V602](C:/Users/Nitya/Desktop/Fluxpay/backend/src/main/resources/db/migration/V602__m5_policy_documents_chunks.sql:16).

### Rules are not equivalent

| Rule | Revised specification | Current prototype |
|---|---|---|
| R1 | Unverified KYC → KYC_UNVERIFIED; unavailable mandatory data → 503 | KYC_UNVERIFIED exists; missing KYC becomes unverified |
| R2 | Earlier COMPLETED payment for same sender/recipient; FIRST_TO_RECIPIENT | Counts all other recipient payments, including failed/later/other-sender; FIRST_TRANSFER_TO_RECIPIENT |
| R3 | Other same-day payment attempts; Asia/Kolkata; RECIPIENT_TODAY | Recipient record created within 24h of payment; RECIPIENT_RECENTLY_ADDED |
| R4 | Source thresholds USD 1000 / EUR 920 / INR 83500; HIGH_VALUE | Fixed approximate FX conversion to USD; AMOUNT_EXCEEDS_THRESHOLD |
| R5 | Known-null or trimmed length under 10; SHORT_PURPOSE | Null skipped; trimmed length under 5; PAYMENT_PURPOSE_MISSING |
| R6 | Uppercase destination country in configured demo list; HIGH_RISK_DEST | Caller-supplied optional boolean; HIGH_RISK_DESTINATION |

Also missing: consistent full snapshots, configuration/clock capture, canonical fingerprints, rule-version/config hashes, positive amount/precision/supported-currency validation, and specified reason order (current code groups HIGH reasons before MEDIUM reasons).

Concurrent assessment creation and concurrent approve/reject have no required head locking/version protocol. Do not add a unique payment_id constraint as a shortcut: the revised specification intentionally permits multiple assessment cycles per payment.

### Documentation conflicts to resolve before sign-off

1. The new specification requires screening_cases and UNDER_REVIEW/PROCESSING, not compliance_cases and OPEN/CLOSED. This needs a coordinated additive migration plus Java/DTO changes, not editing an already-applied migration or blindly mapping CLOSED.
2. The original width is 1536; the Ollama adaptation deliberately uses 768. A matching 768-dimensional model/schema is valid, but this deviation must be reflected in the accepted specification. Do not pad vectors or mix embedding spaces to make dimensions appear to match.
3. No ANN/vector index is required for this small-corpus scope. Its absence is not a defect; exact Oracle vector search is sufficient.
4. Payment state changes, payouts and ledger movement are not M5's responsibility. A successful compliance decision should not be presented as proof that money moved.
5. The optional generated-answer mode is an extension. Prompt instructions alone do not prove its answers/citations are grounded. Use extractive mode for the revised acceptance baseline.
6. Current V604 policy text describes the older rules and some payment-state behavior not present in this backend. It must be aligned too; otherwise Copilot may cite rules the engine does not implement.

## 2. What you can test now

The rest of this guide has two categories:

- **Current-prototype smoke tests:** expected to exercise implemented behavior, including legacy OPEN/CLOSED statuses.
- **Revised-spec acceptance:** several tests cannot pass until the gaps above are implemented.

Use an explicitly designated disposable development schema on the existing Oracle service. Do not run fixture inserts against shared business data. No schema reset, clean, repair or recreation is needed just to test.

### Preparation

1. Use a private local environment file pointing the backend to that chosen test schema on localhost:1521/FREEPDB1. Keep credentials outside version control.
2. Before starting, verify the effective datasource target. The launcher uses `os.environ.setdefault`: `--env-file` does NOT replace inherited environment values. Use a fresh PowerShell session and ensure `ORACLE_JDBC_URL` / `ORACLE_USERNAME` point to the intended test service/schema, with its matching password. Check for higher-priority datasource overrides in `SPRING_DATASOURCE_*`, `SPRING_APPLICATION_JSON`, JVM arguments and active configuration files; remove or reconcile them in that test session. Never print passwords or whole secret-bearing environment values. Confirm the same owner/service in SQL Developer with the read-only connection query in section 4 BEFORE starting a process that can migrate. Stop if the effective target is uncertain.
3. If a backend is already running, stop that instance normally before restarting it on the same port. Do not start extra instances.
4. From C:\Users\Nitya\Desktop\Fluxpay, start the current application:

```powershell
python .\scripts\start-backend.py --port 8081 --env-file <path-to-private-test-env-file>
```

This starts the current production main class and runs its configured Flyway migrations; it is NOT the missing revised-spec solo launcher. If Flyway validation fails, stop and inspect migration history; do not use repair/clean automatically.

5. Check [OpenAPI JSON](http://localhost:8081/v3/api-docs) or [Swagger UI](http://localhost:8081/swagger-ui/index.html).
6. In Postman import [the collection](C:/Users/Nitya/Desktop/Fluxpay/docs/m5-current-api.postman_collection.json).

Collection settings:

| Variable | Value |
|---|---|
| baseUrl | http://localhost:8081 |
| expectedDimensions | 768, only after checking the physical column/provider |
| expectedProvider | MockEmbeddingProvider initially |
| runId | Leave empty; collection initializes a unique value |
| manualPaymentId | Existing dedicated fixture payment ID |
| lowPaymentId / mediumPaymentId / highPaymentId | Fresh fixture payment IDs from the SQL below |

Use **No Auth** for the CURRENT code only. This is a known specification gap, not the intended production setup. Remove inherited bearer headers. JSON requests use Content-Type: application/json. Successful objects/arrays are inside response.data; Swagger's Example Value is not an actual response.

The collection contains 31 requests in four ordered folders. It saves created case/policy IDs automatically. It writes records only when you run it; read the folder descriptions first. Clear runId for a new full policy workflow. Do not rerun only the create-policy request with an unchanged runId unless testing duplicate rejection. Postman supports these saved variables through [collection-variable scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-variables).

### Fresh payment fixtures

V603's six original payments already have precomputed cases. Assessing them returns those cases unchanged; they cannot prove fresh risk evaluation.

For LOW/MEDIUM/HIGH tests use [m5-current-smoke-fixtures.sql](C:/Users/Nitya/Desktop/Fluxpay/docs/m5-current-smoke-fixtures.sql):

- Read it before execution. It inserts synthetic users/KYC/wallets/recipients/payments and COMMITs, without deleting anything.
- Set M5_TEST_SCHEMA to your explicitly designated disposable schema; connect as that owner in SQL Developer.
- Run with F5, then copy the four printed payment IDs into the collection variables.
- It was checked against current migration definitions but not executed during this review.
- Every intentional rerun creates a new fixture batch. The synthetic users are login-disabled and do not supply signed tokens; these are not revised-spec auth fixtures.
- Backend and SQL Developer must connect to the same schema/service. If no designated test schema is available, run only read-only checks until one is provided.

Expected CURRENT results with the fixture and body {"paymentPurpose":"Family support payment","highRiskDestination":false}:

| Payment | Fixture condition | Expected current risk/status |
|---|---|---|
| lowPaymentId | Verified sender, old recipient, historical COMPLETED payment | LOW / CLOSED / SYSTEM_AUTO |
| mediumPaymentId | Verified sender, new recipient, no other payment | MEDIUM / OPEN |
| highPaymentId | Unverified sender, old known recipient | HIGH / OPEN |

The MEDIUM fixture specifically tests the old recipient-age rule, not revised R3's same-day-payment rule.

## 3. Complete current endpoint inventory

All URLs below are for your current Java code. Filters are variants of the same list operation: 13 controller operations total.

| Method | Complete URL | Body | Current success |
|---|---|---|---|
| POST | http://localhost:8081/api/compliance/assess/{{lowPaymentId}} | A | 201, even on replay |
| POST | http://localhost:8081/api/compliance/cases | B | 201 |
| GET | http://localhost:8081/api/compliance/cases | None | 200 |
| GET | http://localhost:8081/api/compliance/cases?status=OPEN | None | 200 |
| GET | http://localhost:8081/api/compliance/cases/{{approveCaseId}} | None | 200 |
| PUT | http://localhost:8081/api/compliance/cases/{{approveCaseId}}/approve | C | 200 |
| PUT | http://localhost:8081/api/compliance/cases/{{rejectCaseId}}/reject | D | 200 |
| POST | http://localhost:8081/api/policies | E | 201 |
| GET | http://localhost:8081/api/policies | None | 200 |
| GET | http://localhost:8081/api/policies/{{policyId}} | None | 200 |
| POST | http://localhost:8081/api/policies/{{policyId}}/chunks | F | 201 |
| GET | http://localhost:8081/api/policies/{{policyId}}/chunks | None | 200 |
| POST | http://localhost:8081/api/policies/{{policyId}}/index | No body | 200 |
| POST | http://localhost:8081/api/copilot/ask | G | 200 |

Other current status filters: APPROVED, REJECTED, CLOSED. Do not send UNDER_REVIEW or PROCESSING and expect current-code success.

A — assessment. Substitute mediumPaymentId/highPaymentId to exercise those fixture scenarios:

```json
{
  "paymentPurpose": "Family support payment",
  "highRiskDestination": false
}
```

B — create a manual case. Create TWO cases, saving one ID as approveCaseId and one as rejectCaseId:

```json
{
  "paymentId": "{{manualPaymentId}}",
  "risk": "MEDIUM",
  "riskReasons": ["MANUAL_REVIEW_REQUIRED"],
  "suggestedAction": "Review synthetic recipient details before approving."
}
```

This endpoint bypasses the rule engine and always creates OPEN, even when risk is LOW.

C — approve:

```json
{
  "decidedBy": "postman.synthetic.reviewer",
  "decisionReason": "Synthetic recipient details checked."
}
```

D — reject the separate OPEN case:

```json
{
  "decidedBy": "postman.synthetic.reviewer",
  "decisionReason": "Synthetic documentation did not resolve the risk."
}
```

Read each case afterward; verify status, decidedBy, decidedAt and decisionReason. Retrying a decided case currently gives 409, including identical approval. This conflicts with the revised replay requirement. Approving an assessed LOW/CLOSED case also gives 409.

E — create a narrative policy:

```json
{
  "title": "M5 synthetic policy {{runId}}",
  "category": "KYC",
  "content": "For synthetic M5 test {{runId}}, a customer must complete identity verification before payment release. An unverified customer must provide a valid identity document before the payment can proceed."
}
```

Categories: KYC, AML, PAYMENT_REVIEW, COUNTRY_RULE, SUPPORT. Repeating the exact title/content returns current 409 CONFLICT. Changing the title currently avoids deduplication, contrary to the revised content-only hash rule. There is no policy update/delete API.

F — manually append one chunk:

```json
{
  "content": "Synthetic manual chunk: complete customer identity verification before payment release."
}
```

This already calls the embedding provider and writes a vector. It is not merely text insertion. The subsequent /index operation replaces all the document's chunks, including this manual chunk, using the original document content.

G — ask a question:

```json
{
  "question": "What should happen if a customer has not completed identity verification?"
}
```

Current response shape:

```json
{
  "correlationId": "<generated value>",
  "data": {
    "answer": "<extracted policy text>",
    "sources": [
      {
        "policyDocumentId": "<real returned policy ID>",
        "title": "<real policy title>",
        "chunkNumber": 1,
        "excerpt": "<source passage>"
      }
    ]
  }
}
```

Do not expect those literal placeholder values. Optional paymentId is accepted by the request DTO but ignored by the current service. It does not currently explain a particular payment's stored assessment.

### Current negative tests

| Request/test | Current expected result |
|---|---|
| POST /api/compliance/cases with {} | 400 VALIDATION |
| Approve/reject with decidedBy blank | 400 VALIDATION |
| POST policy with blank title/content | 400 VALIDATION |
| POST chunk with blank content | 400 VALIDATION |
| POST copilot with {"question":""} | 400 VALIDATION |
| GET unknown well-formed case/policy UUID | 404 NOT_FOUND |
| Assess unknown payment UUID | 404 NOT_FOUND |
| Index or append chunk to unknown policy UUID | 404 NOT_FOUND |
| Repeat an already completed decision | 409 CONFLICT |
| Exact duplicate policy title/content | 409 CONFLICT |
| GET unknown policy's /chunks | 200 with empty array; current implementation does not check parent existence |
| Missing Ollama model/service in Ollama mode | Current provider normally throws 409 CONFLICT; spec requires 503 |

Malformed UUID/JSON/enum and database integrity errors lack complete M5 error mapping. Record the actual response/logs rather than assuming the required status/envelope already works. In particular, an unhandled error can interact with the protected /error route.

## 4. Oracle vector database verification from scratch

Current implemented path:

Policy text → Java chunker → selected embedding provider → float[] through JDBC → policy_chunks.embedding
Question → same embedding space → Oracle cosine VECTOR_DISTANCE → up to five chunks → extractive answer/sources

The application, not Oracle, calls Ollama. Database network ACL/credential setup for DBMS_VECTOR_CHAIN is not required by this implementation.

### Step 1 — verify connection, table definition and baseline

Run in the same schema/service as the backend:

```sql
SELECT SYS_CONTEXT('USERENV','SESSION_USER') AS session_user,
       SYS_CONTEXT('USERENV','CURRENT_SCHEMA') AS current_schema,
       SYS_CONTEXT('USERENV','CON_NAME') AS container_name,
       SYS_CONTEXT('USERENV','SERVICE_NAME') AS service_name
FROM dual;

SELECT banner FROM v$version WHERE banner LIKE 'Oracle%';

SELECT "version", "description", "success"
FROM "flyway_schema_history"
ORDER BY "installed_rank";

SELECT COUNT(*) AS documents FROM policy_documents;
SELECT COUNT(*) AS chunks FROM policy_chunks;

SET LONG 100000
SELECT DBMS_METADATA.GET_DDL('TABLE','POLICY_CHUNKS') FROM dual;
```

If your account cannot query v$version, obtain the version from your DBA; do not grant extra privileges merely for this guide.

Current V602 defines VECTOR(768,FLOAT32). Check the physical column: changing an applied SQL file or repairing a checksum does not change an existing column. If the actual width is 1536, stop before indexing and reconcile the model/schema through a reviewed additive change. Do not reset the schema to hide the mismatch.

V604's seed-only expectation is 13 documents and no seeded chunks; it deletes five stale placeholders. Additional manual/API data changes these totals. Empty policy_chunks before indexing is expected.

### Step 2 — mock vector storage and deterministic retrieval

For a matching 768-column test schema, explicitly configure the current backend in PowerShell before startup:

```powershell
$env:EMBEDDING_MODE = 'mock'
$env:SPRING_APPLICATION_JSON = '{"fluxpay":{"embedding-mode":"mock","embedding-dimensions":768,"copilot-generation-mode":"extractive"}}'
python .\scripts\start-backend.py --port 8081 --env-file <path-to-private-test-env-file>
```

If SPRING_APPLICATION_JSON already contains important settings, merge these fields instead of replacing it. This process-local override requires no shared YAML edits; Spring Boot supports [inline JSON external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html).

Run collection folder 04 in order. Expect one generated chunk for its short test document, with dimensions 768 and embeddingProvider MockEmbeddingProvider. The collection reads back the indexed chunk and asks with exactly that text: identical mock input produces an identical vector, so the source should be that test document. This tests the pipeline, not semantic understanding.

Read the actual database vectors:

```sql
SELECT COUNT(*) AS total_chunks,
       COALESCE(SUM(CASE WHEN embedding IS NOT NULL THEN 1 ELSE 0 END), 0) AS with_vectors
FROM policy_chunks;

SELECT VECTOR_DIMENSION_COUNT(embedding) AS dimensions, COUNT(*) AS vector_count
FROM policy_chunks
WHERE embedding IS NOT NULL
GROUP BY VECTOR_DIMENSION_COUNT(embedding);

SELECT RAWTOHEX(pc.id) AS chunk_id, pd.title, pc.chunk_number,
       VECTOR_DIMENSION_COUNT(pc.embedding) AS dimensions,
       VECTOR_DISTANCE(pc.embedding, pc.embedding, COSINE) AS self_distance
FROM policy_chunks pc
JOIN policy_documents pd ON pd.id = pc.policy_document_id
WHERE pc.embedding IS NOT NULL
ORDER BY pd.title, pc.chunk_number;
```

Expect stored dimensions 768 and self-distance approximately zero. The /index response alone reports provider configuration, not an independent measurement of Oracle's stored data. Oracle documents [VECTOR_DIMENSION_COUNT](https://docs.oracle.com/en/database/oracle/oracle-database/26/vecse/vector_dimension_count-vecse.html) for this check.

### Step 3 — direct nearest-neighbor SQL

Replace YOUR_TEST_POLICY_UUID with the policyId returned by Postman:

```sql
WITH query_vector AS (
  SELECT embedding
  FROM policy_chunks
  WHERE policy_document_id = HEXTORAW(REPLACE('YOUR_TEST_POLICY_UUID', '-', ''))
    AND chunk_number = 1
    AND embedding IS NOT NULL
)
SELECT RAWTOHEX(pd.id) AS document_id, pd.title, pc.chunk_number,
       VECTOR_DISTANCE(pc.embedding, q.embedding, COSINE) AS distance
FROM policy_chunks pc
JOIN policy_documents pd ON pd.id = pc.policy_document_id
CROSS JOIN query_vector q
WHERE pc.embedding IS NOT NULL
ORDER BY distance, pd.id, pc.chunk_number
FETCH FIRST 5 ROWS ONLY;
```

This uses a stored document vector as the query: its own matching row should be among the nearest results at approximately zero distance (identical other vectors can tie). It proves SQL vector ranking, not a natural-language query. Your API's RAW IDs serialize as hyphenated UUIDs; the SQL removes hyphens for comparison.

A CREATE VECTOR INDEX statement is not required. /index means chunk/embed/store in this application; it does not create an Oracle ANN index. Exact nearest-neighbor SQL is native vector search too. See [Oracle exact similarity search](https://docs.oracle.com/en/database/oracle/oracle-database/26/vecse/perform-exact-similarity-search.html).

### Step 4 — real semantic embeddings with Ollama

First confirm the local model/service, without starting a second server if one is already running:

```powershell
ollama list
# Only if nomic-embed-text is absent:
ollama pull nomic-embed-text
```

Test the endpoint that YOUR CURRENT JAVA calls in Postman:

```text
POST http://localhost:11434/api/embeddings
Content-Type: application/json
```

```json
{
  "model": "nomic-embed-text",
  "prompt": "search_query: Why does an unverified customer need payment review?"
}
```

Check that response.embedding is a numeric array of length 768. If your installed Ollama does not support that endpoint, the current adapter needs updating. Do not merely substitute /api/embed: the modern [Ollama endpoint](https://docs.ollama.com/api/embed) uses input and returns embeddings (plural), so the Java request/parser must change together.

Stop the backend normally, then restart it with exact properties:

```powershell
$env:EMBEDDING_MODE = 'ollama'
$env:SPRING_APPLICATION_JSON = '{"fluxpay":{"embedding-mode":"ollama","embedding-api-url":"http://localhost:11434","embedding-model":"nomic-embed-text","embedding-dimensions":768,"copilot-generation-mode":"extractive"}}'
python .\scripts\start-backend.py --port 8081 --env-file <path-to-private-test-env-file>
```

Again merge any existing JSON settings. Only EMBEDDING_MODE is currently mapped in application.yml. The README's bare EMBEDDING_MODEL/EMBEDDING_DIMENSIONS/EMBEDDING_API_URL/COPILOT_GENERATION_MODE names do not independently populate all the required fluxpay.* keys. Using exact JSON properties avoids silently relying on defaults.

In Postman set expectedProvider=OllamaEmbeddingProvider and expectedDimensions=768. **Reindex every policy before asking semantic questions.** Current storage has no model/space metadata; mixing old mock and new semantic vectors gives misleading results even though both are 768-dimensional.

Get IDs using GET http://localhost:8081/api/policies. For EACH returned ID, run POST http://localhost:8081/api/policies/<ID>/index with no body. Optional local PowerShell equivalent, only against the designated test backend:

```powershell
$m5Base = 'http://localhost:8081'
$m5Docs = (Invoke-RestMethod "$m5Base/api/policies" -ErrorAction Stop).data
foreach ($m5Doc in $m5Docs) {
  $m5Indexed = Invoke-RestMethod -Method Post -Uri "$m5Base/api/policies/$($m5Doc.id)/index" -ErrorAction Stop
  $m5Indexed.data
}
```

This rebuilds document chunks, including replacing manually appended chunks. Do not use Copilot partway through a provider switch. If any index fails, stop, fix that failure and finish all documents before evaluating results. Switching back to mock requires the same full reindex.

For exactly the unmodified 13 V604 documents, the current chunker yields 14 chunks: the 718-word AML document becomes two chunks (550 and 218 words with 50 overlap), the other documents one each. Custom policies add more. Reindexing twice should keep counts stable, although current code replaces chunk IDs and makes provider calls again; the revised spec requires a no-op replay instead.

### Step 5 — semantic and failure tests

Ask these one at a time through POST http://localhost:8081/api/copilot/ask:

| Question | What to inspect, not a guaranteed exact ranking |
|---|---|
| Why must an unverified customer complete KYC before sending money? | KYC source title and supporting excerpt |
| Why does a first-time recipient need review? | New-recipient policy among relevant sources |
| What happens when the payment purpose is missing? | Purpose policy; note current code/spec/corpus disagreement |
| When should a high-value transfer require source-of-funds checks? | High-value/AML sources; no invented thresholds |
| How is a failed payout different from a compliance hold? | Hold/recovery policies; answer does not execute anything |

Also test:

- Rephrase a question: relevant policies should remain relevant; evaluate meaning, not byte-identical answers.
- Ask an unrelated question (e.g. a cooking recipe). Current code may still return policy excerpts because it lacks a distance threshold. This reveals a known failure against the revised no-answer requirement.
- Inspect each returned source using GET /api/policies/<source policyDocumentId>/chunks; verify the chunk number/text exists.
- Test empty-corpus behavior only in a separate empty test schema; do not delete shared policies. Current empty-result wording is not the required exact text.
- Provider failure: on the isolated backend use a deliberately nonexistent embedding model, restart, and attempt reindex of the test policy. Current expected failure is normally 409. Verify previous chunks/vectors still exist after rollback; this transaction behavior has not yet been proven with Oracle tests.
- Restore the real model and reindex consistently before further questions.
- Optional generation: with embeddings healthy, configure an absent chat model and generation-mode=ollama to test extractive fallback. A failed embedding call happens before that fallback and still fails the request. Restore extractive mode for baseline tests.

No quality score is claimed by this review. Mock tests and a successful HTTP 200 do not establish semantic relevance or complete grounding.

## 5. Revised-spec acceptance gates — not currently passing

These must be implemented and tested separately; do not change the current Postman assertions and then declare acceptance.

1. **Schema/contract reconciliation:** screening_cases extensions, proper status width/values, complete assessment metadata, head rows, durable decisions, active policy generations, chosen vector width; clean and evidence-based upgrade migrations.
2. **Rules:** R1–R6 with exact reason names/order; EUR 920/921 and INR 83500/83500.0001 boundaries; purpose null/9/10 characters; same-day history at Asia/Kolkata midnight; unavailable inputs return 503; one MEDIUM stays visible on LOW.
3. **Assessment cycles:** new UUID/sequence gives 201; exact replay gives 200 without live lookups; changed request same ID gives 409; new cycle re-evaluates; older sequence cannot replace the latest case.
4. **Active review:** recordDisposition; reviewable only for latest active review; approve/reject with principal identity; same decision replay returns one recorded decision; concurrent conflicting reviewers produce one winner.
5. **Delivery:** case + delivery row commit atomically; lost acknowledgment retries same decision ID; fixture sink effect occurs once; stale reference becomes conflict without undoing the case decision.
6. **HTTP/security:** missing token 401 AUTH_REQUIRED; non-admin 403 FORBIDDEN; foreign-owner passport not-found; correct filter/pagination/body/error envelopes. Do not use caller-supplied decidedBy as identity.
7. **Policies:** normalized-content duplicate 409 with existingPolicyDocumentId; all size limits; boundary-aware chunking; finite/nonzero/correct-width vectors; overall deadlines; no provider I/O while holding publication locks; no-op identical indexing; concurrent generation checks.
8. **Oracle:** native UUID/vector round trips, exact ordering with controlled vectors, constraint checks, concurrent case/review/index publication, rollback preservation and model-space isolation. Real SQL tests, not H2 or Java-only cosine.
9. **Copilot:** mock flag, authorized latest stored caseContext, supported excerpts and threshold filtering; empty/incompatible/unrelated results exactly "No grounded answer found in current policies." with sources=[].
10. **Solo test harness:** test-only fixtures/readers/sink/signed tokens, isolated-schema guard and specified scripts. Each required test group must execute nonzero tests.
11. **UI and integration:** implement/render the three src/ts M5 components and fixture/live harness; separately verify shared routing/auth, M4 mount and actual M3 delivery. This review inspected file presence only.

Example REQUIRED assessment contract (not supported by current code):

```text
POST http://localhost:8081/api/compliance/assess/<fixture-payment-uuid>
Authorization: Bearer <signed-admin-token>
```

```json
{
  "assessmentId": "<new-uuid-retained-for-retries>",
  "assessmentSequence": 1,
  "expectedPaymentFingerprint": "<actual-64-character-fixture-fingerprint>"
}
```

These placeholders must come from the specified fixture metadata; do not invent fingerprints. That fixture endpoint and launcher are absent today. Required rejection body is {"reason":"Document evidence was insufficient."}, with reviewer taken from authentication; current code requires the older body shown above.

Recommended order: resolve specification-versus-prototype contracts first; implement/test compliance lifecycle and security; implement vector publication/grounding safeguards; then run full isolated Oracle and Postman acceptance. You can run the current smoke tests now to establish a baseline, but they are not a completion sign-off.
