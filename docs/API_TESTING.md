# FluxPay Payments API Testing

## Postman collection

Import [FluxPay-Payments.postman_collection.json](postman/FluxPay-Payments.postman_collection.json) into Postman. The collection covers every route currently exposed by the payment and recipient controllers.

Before running it, set `walletId` to a real USD wallet owned by `localUserId`. The previous local wallet mock has been removed, so a placeholder value will correctly return `WALLET_NOT_FOUND` or fail the database relationship validation.

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

## Live test evidence

Before mock removal, the local backend was exercised successfully through the complete flow above against Oracle. The backend currently still responds to `/v3/api-docs`; the collection is ready to run again once the real wallet/KYC/ledger/provider implementations are integrated and `walletId` is configured.

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
