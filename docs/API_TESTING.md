# FluxPay Payments API Testing

## Postman collection

Import [FluxPay-Payments.postman_collection.json](postman/FluxPay-Payments.postman_collection.json) into Postman. The collection covers every route currently exposed by the payment and recipient controllers.

For the local profile, the collection's default `walletId` is provisioned by the test fixture as an owned USD wallet. Replace it with a real owned wallet when testing against integrated wallet services.

For local development, the collection uses `X-Local-User-Id`; that header is accepted only by the `local` Spring profile. Production testing must use a valid bearer token instead.

## Coverage and expected status

| Area | Request | Expected status |
| --- | --- | --- |
| Recipients | Create | 201 |
| Recipients | List | 200 |
| Recipients | Update | 200 |
| Payments | Create draft | 201 |
| Payments | Create quotes | 201 |
| Payments | Get quotes | 200 |
| Payments | Confirm payment | 200 |
| Payments | Get payment detail | 200 |
| Payments | List payments | 200 |
| Payments | Create draft for cancellation | 201 |
| Payments | Cancel payment | 200, status `CANCELLED` |

## Test order

Run the collection in its displayed order. The Postman test scripts save the recipient, payment, quote, and cancellation payment identifiers for later requests.

## Live Postman test evidence

On 2026-09-11, the Postman CLI bundled with Postman Desktop ran this local collection against Oracle successfully:

| Metric | Result |
| --- | ---: |
| Requests | 11 passed / 0 failed |
| Assertions | 13 passed / 0 failed |
| Average response time | 34 ms |
| Total duration | 1.345 s |

The test setup provisions a deterministic local test user and USD wallet. `M3PaymentTestConfig` is restricted to the Spring `local` profile and supplies wallet, KYC, ledger, FX, payout, and embedding adapters only while the owning modules are unavailable. Production must supply the real integrations.

## Negative checks

Run these manually after the happy path:

| Scenario | Expected result |
| --- | --- |
| Missing `Idempotency-Key` on draft or confirm | 400 |
| Same source and payout currency | 422 `INVALID_CORRIDOR` |
| Blocked recipient | 422 `RECIPIENT_UNAVAILABLE` |
| Unowned/nonexistent recipient | 404 `RECIPIENT_NOT_FOUND` |
| Invalid or expired quote | 409 |
| Stale recipient `expectedVersion` | 409 `RECIPIENT_VERSION_CONFLICT` |
