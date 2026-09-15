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

The collection uses realistic Indian sample details (Ananya Sharma, HDFC Bank, and ICICI Bank). `account` values intentionally start with `TEST-` and include a generated UUID: they are fictional test identifiers, not real bank account numbers.

## Manual Postman setup

Create a Postman environment with these values:

| Variable | Local value | Purpose |
| --- | --- | --- |
| `baseUrl` | `http://localhost:8080` | Backend address |
| `localUserId` | `11111111-1111-1111-1111-111111111111` | Local test customer identity |
| `walletId` | `22222222-2222-2222-2222-222222222222` | Fixture USD wallet |
| `recipientId` | empty initially | Store `data.id` from Create recipient |
| `recipientVersion` | `0` initially | Store `data.version` from Create/update recipient |
| `paymentId` | empty initially | Store `data.id` from Create draft |
| `quoteId` | empty initially | Store `data.recommendedQuoteId` from Create quotes |
| `cancelPaymentId` | empty initially | Store `data.id` from cancellation draft |

For every local request, add this header:

```text
X-Local-User-Id: {{localUserId}}
```

For `POST /api/payments/draft` and `POST /api/payments/{id}/confirm`, also add a unique header:

```text
Idempotency-Key: {{$guid}}
```

## Local mock and fixture inventory

`M3PaymentTestConfig` is active only with the Spring `local` profile. It is deliberately not a production implementation.

| Dependency owned elsewhere | Local test behavior |
| --- | --- |
| Authentication | `JwtAuthFilter` accepts `X-Local-User-Id` only in the local profile. |
| User | Fixture user `11111111-1111-1111-1111-111111111111` is merged into Oracle. |
| Wallet | Fixture wallet `22222222-2222-2222-2222-222222222222` is merged as owned USD wallet with balance `1000000`. |
| Wallet port | Returns an eligible owned USD wallet with `1000000.0000` available funds. |
| KYC | Always verified. |
| Compliance screening | Always approved. |
| FX provider | Fixed USD-to-INR test rate of `0.900000`. |
| Ledger writer | No-op; it lets payment confirmation be tested without another member's ledger module. |
| Payout provider | Returns `local-{paymentId}`. |
| Embedding provider | Returns a single zero vector. |

The fixture is provisioned automatically by `scripts/test-api-smoke.py`. For a manual-only Postman session, compile and run `scripts/_ensure_api_smoke_user.java` with the Oracle JDBC driver available, or first run the smoke script once.

## Every API: Postman request data

### 1. Create recipient

```http
POST {{baseUrl}}/api/recipients
Content-Type: application/json
X-Local-User-Id: {{localUserId}}
```

```json
{
  "name": "Ananya Sharma",
  "account": "TEST-HDFC-{{$randomUUID}}",
  "bankName": "HDFC Bank",
  "country": "IN",
  "currency": "INR",
  "status": "ACTIVE"
}
```

Expected: `201`. Save `response.data.id` as `recipientId` and `response.data.version` as `recipientVersion`.

### 2. List recipients

```http
GET {{baseUrl}}/api/recipients
X-Local-User-Id: {{localUserId}}
```

Expected: `200`, with `data` as an array.

### 3. Update recipient

```http
PUT {{baseUrl}}/api/recipients/{{recipientId}}
Content-Type: application/json
X-Local-User-Id: {{localUserId}}
```

```json
{
  "name": "Ananya Sharma",
  "account": "TEST-ICICI-{{$randomUUID}}",
  "bankName": "ICICI Bank",
  "country": "IN",
  "currency": "INR",
  "status": "ACTIVE",
  "expectedVersion": {{recipientVersion}}
}
```

Expected: `200`. Save the returned `data.version` as `recipientVersion`.

### 4. Create payment draft

```http
POST {{baseUrl}}/api/payments/draft
Content-Type: application/json
X-Local-User-Id: {{localUserId}}
Idempotency-Key: {{$guid}}
```

```json
{
  "sourceWalletId": "{{walletId}}",
  "recipientId": "{{recipientId}}",
  "sourceAmount": 100,
  "sourceCurrency": "USD",
  "payoutCurrency": "INR",
  "purpose": "FAMILY_SUPPORT",
  "preference": "CHEAPEST"
}
```

Expected: `201`. Save `data.id` as `paymentId`.

### 5. Create payment quotes

```http
POST {{baseUrl}}/api/payments/{{paymentId}}/quotes
X-Local-User-Id: {{localUserId}}
```

Expected: `201`. Save `data.recommendedQuoteId` as `quoteId`.

### 6. Get payment quotes

```http
GET {{baseUrl}}/api/payments/{{paymentId}}/quotes
X-Local-User-Id: {{localUserId}}
```

Expected: `200` and a `data.quotes` array.

### 7. Confirm payment

```http
POST {{baseUrl}}/api/payments/{{paymentId}}/confirm
Content-Type: application/json
X-Local-User-Id: {{localUserId}}
Idempotency-Key: {{$guid}}
```

```json
{ "quoteId": "{{quoteId}}" }
```

Expected: `200`, with payment status `PROCESSING` under the local approval mock.

### 8. Get payment detail

```http
GET {{baseUrl}}/api/payments/{{paymentId}}
X-Local-User-Id: {{localUserId}}
```

Expected: `200`.

### 9. List payments

```http
GET {{baseUrl}}/api/payments?page=0&size=20
X-Local-User-Id: {{localUserId}}
```

Expected: `200`, with `data.items`, `data.page`, `data.size`, and `data.total`.

### 10. Create cancellation draft

Use the same request as Create payment draft, but change `sourceAmount` to `50`. Save the returned `data.id` as `cancelPaymentId`.

Expected: `201`.

### 11. Cancel payment

```http
POST {{baseUrl}}/api/payments/{{cancelPaymentId}}/cancel
X-Local-User-Id: {{localUserId}}
```

Expected: `200` and `data.status` equal to `CANCELLED`.

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
