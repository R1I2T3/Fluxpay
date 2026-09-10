# Member 4 — five-minute backend demo (PRD §14 smoke)

Needs: `python scripts/start-infra.py` (Oracle + bare-metal KRaft topics), backend on
`http://localhost:8080` with the `mock` profile, and `X-Correlation-ID` on every call.

## 0. Auth — real JWT, not `mock.*`

`Bearer mock.<uuid>.CUSTOMER` only works in `@WebMvcTest` via `MockSecurity.stubJwt`.
The real backend parses HS256 JWT in `JwtUtil.parse` and checks ownership in
`StubRouteAdminAuthorizer.isOwner` (`payment.senderUserId == user.userId`).
A `mock.*` token yields `403 FORBIDDEN`. Mint real tokens with the same `JWT_SECRET`
as the backend (see `.env.example`, min 32 bytes).

Sender owners (`InMemoryPaymentReader`, `UUID.nameUUIDFromBytes`):

```bash
# P-001 sender: a33b2576-b27c-35ca-92f3-84e4133eddcb (fluxpay:P-001:user, $1000 USD->KES)
# P-002 sender: 812d0a3f-0864-3072-b31b-c5eaaca9c36d (fluxpay:P-002:user, $500 USD->KES)
export JWT_SECRET='change-me-32-bytes-minimum-0123456789abcdef' # must match backend .env
export P001_JWT=$(python3 -c "
import jwt,time,os
s=os.environ['JWT_SECRET']; u='a33b2576-b27c-35ca-92f3-84e4133eddcb'; n=int(time.time())
print(jwt.encode({'sub':u,'email':u+'@example.com','role':'CUSTOMER','iat':n,'exp':n+3600},s,algorithm='HS256'))")
export P002_JWT=$(python3 -c "
import jwt,time,os
s=os.environ['JWT_SECRET']; u='812d0a3f-0864-3072-b31b-c5eaaca9c36d'; n=int(time.time())
print(jwt.encode({'sub':u,'email':u+'@example.com','role':'CUSTOMER','iat':n,'exp':n+3600},s,algorithm='HS256'))")
export ADMIN_JWT=$(python3 -c "
import jwt,time,os
s=os.environ['JWT_SECRET']; u='00000000-0000-0000-0000-000000000001'; n=int(time.time())
print(jwt.encode({'sub':u,'email':'admin@example.com','role':'ADMIN','iat':n,'exp':n+3600},s,algorithm='HS256'))")
```

Start the backend with the same secret and `mock` profile:

```bash
python scripts/start-backend.py --profile mock
```

Notes:
- Use `P001_JWT` for `/payments/P-001/*`, `P002_JWT` for `/payments/P-002/*`. Wrong owner → `403 FORBIDDEN`.
- Use `ADMIN_JWT` (role `ADMIN`) only for `PUT /api/admin/routes/{routeId}`.
- `401/403` almost always means wrong owner token or `JWT_SECRET` mismatch.

## 1. Recommend — `marketRate` on every quote

```bash
CID=demo-reco-1
curl -s -X POST localhost:8080/api/payments/P-001/recommend-route \
  -H "Authorization: Bearer $P001_JWT" \
  -H "X-Correlation-ID: $CID" -H "Content-Type: application/json" \
  -d '{"preference":"BALANCED"}' | jq '{cid: .correlationId, quotes: [.data.quotes[] | {routeId, marketRate, offeredRate}]}'
```

Expect `200`, echoed `$CID`, two+ quotes each with `marketRate` (mock `USD->KES 148.0000`;
other pairs are rejected by `MockFxRateProvider`).

## 2. Submit happy path — `COMPLETED`

```bash
CID=demo-submit-1
curl -s -X POST localhost:8080/api/payments/P-001/submit-payout \
  -H "Authorization: Bearer $P001_JWT" \
  -H "X-Correlation-ID: $CID" -H "Idempotency-Key: demo-key-1" \
  -H "Content-Type: application/json" \
  -d '{"routeCode":"STANDARD_BANK"}' | jq '{cid: .correlationId, data}'
```

With `SIMULATE_FAILURE` unset the `StandardBankAdapter` succeeds: `status COMPLETED`.
First execution returns `alreadyConfirmed:false` with `originalEventId:null`; only a replay
with the same `Idempotency-Key` returns `alreadyConfirmed:true` + the original id.
A failed execution releases the key so the same key can be retried.

## 3. Fail, then switch to `INSTANT_PAYOUT`

Uses the second seeded payment `P-002` with its own owner token:

```bash
SIMULATE_FAILURE=STANDARD_BANK  # restart backend with this env (fails forever)
# Or transient: SIMULATE_FAILURE=STANDARD_BANK:2 fails next 2 attempts per payment, then succeeds.
curl -s -X POST localhost:8080/api/payments/P-002/submit-payout \
  -H "Authorization: Bearer $P002_JWT" \
  -H "X-Correlation-ID: demo-submit-2" -H "Idempotency-Key: demo-key-2" \
  -H "Content-Type: application/json" -d '{"routeCode":"STANDARD_BANK"}' | jq .data.status
# → "FAILED"
curl -s -X POST localhost:8080/api/payments/P-002/switch-route \
  -H "Authorization: Bearer $P002_JWT" \
  -H "X-Correlation-ID: demo-switch-1" -H "Idempotency-Key: demo-key-3" \
  -H "Content-Type: application/json" -d '{"routeCode":"INSTANT_PAYOUT"}' | jq .data.status
# → "COMPLETED" (instant adapter always succeeds; switching to the same route is rejected)
```

`retry-payout` uses the same `P002_JWT` owner and an `Idempotency-Key`. Retry/switch after
a refund is rejected (`payment already refunded`) to prevent payout-after-refund.

## 4. Refund a failed payment — balanced entries + timeline row

```bash
curl -s -X POST localhost:8080/api/payments/P-002/refund \
  -H "Authorization: Bearer $P002_JWT" \
  -H "X-Correlation-ID: demo-refund-1" | jq .data
curl -s localhost:8080/api/payments/P-002/timeline \
  -H "Authorization: Bearer $P002_JWT" \
  -H "X-Correlation-ID: demo-time-1" | jq '[.data[] | .eventType]'
# includes "payment.refunded"; the journal posts clearing-DEBIT + sender-CREDIT and never
# touches the original debit key (see RefundJournalServiceTest). Fast double-refund replays
# via ledger idempotency keys without duplicate publish, even before timeline ingestion.
```

## 5. Persistence proof

```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ORACLE_JDBC_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 \
  ./mvnw -f backend/pom.xml -Dtest=PaymentEventPersistenceIT test
# or: python scripts/test-all.py   (runs it when both env vars are set, skips otherwise)
```

A Kafka-produced `payout.submitted` envelope becomes a `payment_events` row; the duplicate
`eventId` is acknowledged once. Poison envelopes are quarantined to `payout.recovery.dlt`
(see `04-member4-baremetal-kafka.md`); infra publish failures surface as `503 EVENT_PUBLISH_FAILED`.
