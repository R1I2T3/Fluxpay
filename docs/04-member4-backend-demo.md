# Member 4 — five-minute backend demo (PRD §14 smoke)

Needs: `python scripts/start-infra.py` (Oracle + bare-metal KRaft topics), backend on
`http://localhost:8080` with the `mock` profile, `mock` JWT (`TestAuthHelper` style) owned by the
P-001 sender (`fluxpay:P-001:user`), and `X-Correlation-ID` on every call.

## 1. Recommend — `marketRate` on every quote

```bash
CID=demo-reco-1
curl -s -X POST localhost:8080/api/payments/P-001/recommend-route \
  -H "Authorization: Bearer mock.<sender-user-uuid>.CUSTOMER" \
  -H "X-Correlation-ID: $CID" -H "Content-Type: application/json" \
  -d '{"preference":"BALANCED"}' | jq '{cid: .correlationId, quotes: [.data.quotes[] | {routeId, marketRate, offeredRate}]}'
```

Expect `200`, echoed `$CID`, two+ quotes each with `marketRate` (frozen `FxRateProvider`).

## 2. Submit happy path — `COMPLETED`

```bash
CID=demo-submit-1
curl -s -X POST localhost:8080/api/payments/P-001/submit-payout \
  -H "Authorization: Bearer mock.<sender-user-uuid>.CUSTOMER" \
  -H "X-Correlation-ID: $CID" -H "Idempotency-Key: demo-key-1" \
  -H "Content-Type: application/json" \
  -d '{"routeCode":"STANDARD_BANK"}' | jq '{cid: .correlationId, data}'
```

With `SIMULATE_FAILURE` unset the `StandardBankAdapter` succeeds: `status COMPLETED`.

## 3. Fail, then switch to `INSTANT_PAYOUT`

On a second payment (e.g. `P-002`):

```bash
SIMULATE_FAILURE=STANDARD_BANK  # restart backend with this env
curl -s -X POST localhost:8080/api/payments/P-002/submit-payout \
  -H "Authorization: Bearer mock.<sender-user-uuid>.CUSTOMER" \
  -H "X-Correlation-ID: demo-submit-2" -H "Idempotency-Key: demo-key-2" \
  -H "Content-Type: application/json" -d '{"routeCode":"STANDARD_BANK"}' | jq .data.status
# → "FAILED"
curl -s -X POST localhost:8080/api/payments/P-002/switch-route \
  -H "Authorization: Bearer mock.<sender-user-uuid>.CUSTOMER" \
  -H "X-Correlation-ID: demo-switch-1" -H "Idempotency-Key: demo-key-3" \
  -H "Content-Type: application/json" -d '{"routeCode":"INSTANT_PAYOUT"}' | jq .data.status
# → "COMPLETED" (instant adapter always succeeds; switching to the same route is rejected)
```

## 4. Refund a failed payment — balanced entries + timeline row

```bash
curl -s -X POST localhost:8080/api/payments/P-002/refund \
  -H "Authorization: Bearer mock.<sender-user-uuid>.CUSTOMER" \
  -H "X-Correlation-ID: demo-refund-1" | jq .data
curl -s localhost:8080/api/payments/P-002/timeline \
  -H "Authorization: Bearer mock.<sender-user-uuid>.CUSTOMER" \
  -H "X-Correlation-ID: demo-time-1" | jq '[.data[] | .eventType]'
# includes "payment.refunded"; the journal posts clearing-DEBIT + sender-CREDIT and never
# touches the original debit key (see RefundJournalServiceTest).
```

## 5. Persistence proof

```bash
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 ORACLE_JDBC_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 \
  ./mvnw -f backend/pom.xml -Dtest=PaymentEventPersistenceIT test
# or: python scripts/test-all.py   (runs it when both env vars are set, skips otherwise)
```

A Kafka-produced `payout.submitted` envelope becomes a `payment_events` row; the duplicate
`eventId` is acknowledged once.
