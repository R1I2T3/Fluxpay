# FluxPay Bruno API Catalog

This catalog documents the original 46 HTTP API endpoints, followed by the September 20
integration additions, the payment-operations read API, and the admin statistics read
APIs below.

## Payment operations event topics

The local scripts explicitly provision these eleven Kafka topics, in this order:

1. `payment.initiated`
2. `payment.route.selected`
3. `payment.screening.completed`
4. `payment.review.requested`
5. `payout.submitted`
6. `payout.failed`
7. `payout.retry`
8. `payout.refund`
9. `payout.completed`
10. `payment.refunded`
11. `payout.recovery.dlt`

`payout.retry` and `payout.refund` are internal recovery commands. They are visible in
the admin operations read model, but are not customer timeline events. A delivery state
of `SENT` proves Kafka publication only; a matching row in `timelineEvents` proves that
the timeline consumer persisted the lifecycle event.

### Local payment-operations demo contract

The local-only configuration for the two scenarios is:

```dotenv
FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true
FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_ROUTE_CODE=BANK_STANDARD
FLUXPAY_DEVELOPMENT_RETRY_SUCCESS_FAILURE_ATTEMPTS=2
FLUXPAY_DEVELOPMENT_REFUND_ROUTE_CODE=BANK_EXPRESS
FLUXPAY_DEVELOPMENT_REFUND_FAILURE_ATTEMPTS=6
FLUXPAY_DEVELOPMENT_RECOVERY_DELAY_SECONDS=5
```

The existing seeded customer must have **verified KYC**, a **funded USD wallet**, and
an **active INR recipient** before either command runs. In other words, the runbook
requires a verified KYC session, a funded USD wallet, and an active INR recipient.
Prepare those prerequisites
through the existing UI or APIs; the script never fabricates them. From a running
stack, run:

```bash
python3 -B scripts/demo-payment-operations.py retry-success
python3 -B scripts/demo-payment-operations.py refund-exhaustion
```

`BANK_STANDARD` must show two definitive failures followed by a successful automatic
retry, `payout.completed`, and final `COMPLETED`. `BANK_EXPRESS` must show six failed
attempts, five `AUTO_RETRY` operations, an immediate `payout.refund`, refund ledger
entries, `payment.refunded`, and final `REFUNDED`. Both `payout.retry` and
`payout.refund` outbox deliveries reach `SENT`; lifecycle rows prove timeline
consumer persistence separately. The complete startup/reset/Flyway/seed sequence,
evidence interpretation, 5–8 minute presenter target, and verification commands are
in the [README runbook](../README.md#payment-operations-event-flow-demonstration).

## September 20 integration additions

All wallet/bank writes below require a bearer token and `Idempotency-Key`. Bank-account listing is authenticated and scoped to the current user; full account numbers are never accepted or returned.

| Method and URL | Request / behavior | Frontend location |
| --- | --- | --- |
| `GET /api/bank-accounts` | Returns `[{id,bankName,accountLast4,currency,status}]` for the authenticated owner only. | Wallets → Your linked banks |
| `POST /api/bank-accounts/link` | `{bankName:"Example Bank",accountLast4:"4321",currency:"USD"}`. Exactly four digits; no full bank number. | Wallets / Add money → Link bank |
| `POST /api/bank-accounts/{id}/topup` | `{amount:"25.00",note:"Monthly savings"}`. Bank must be owned, verified, and currency-matched. Verified KYC required; daily cap 10,000 per currency. | Add money → Linked bank account → Review |
| `POST /api/wallets/withdraw` | `{bankAccountId:"<uuid>",currency:"USD",amount:"10.00",note:"Savings"}`. Requires available balance and matching verified bank. | Wallets → Withdraw to bank |
| `POST /api/wallets/transfer` | `{toEmail:"recipient@example.test",fromCurrency:"USD",toCurrency:"INR",amount:"10.00",amountMode:"SOURCE",note:"Lunch"}`. Supply exactly one of `toEmail` or `toUserId`. `SOURCE` sets sender amount; `TARGET` sets recipient amount. The 200 response also returns the winning internal route decision (`providerCode`, `routeCode`, `railType`, `effectiveReliability`); replays return the stored decision. | Wallets → Pay a FluxPay wallet |
| `PUT /api/policies/{id}` | `{title,category,content,clearExistingChunks}`; clearing/replacing content invalidates the search index. | Administration → Policy library → Edit |
| `PUT /api/policies/{id}/chunks/{chunkId}` | `{content}`; manual chunks only. | Policy library → Advanced settings |
| `DELETE /api/policies/{id}/chunks/{chunkId}` | Deletes a chunk. Reindexing can recreate generated chunks. | Policy library → Advanced settings |
| `GET /api/policies/{id}/guidance` | Lists case-linked guidance. | Policy library → Advanced settings |
| `POST /api/policies/{id}/guidance` | `{complianceCaseId,content}`; completed case required. | Policy library → Add guidance |
| `PUT /api/policies/{id}/guidance/{guidanceId}` | `{content}`. | Policy library → Edit guidance |
| `DELETE /api/policies/{id}/guidance/{guidanceId}` | Removes guidance. | Policy library → Delete guidance |
| `POST /api/copilot/ask/stream` | Admin-only `{question,paymentId?}`. SSE `data:{delta}` frames and an `event:done` frame. This stream does not include source citations. | Compliance Copilot → Live response; Get cited answer uses the existing `/ask` endpoint |

Wallet amounts use each currency's configured precision (currently 2 decimal places); notes are at most 255 characters. `REQUOTE_REQUIRED`, insufficient funds, bank mismatch, KYC, and daily-cap errors are shown without automatically retrying a financial operation. Bank actions in the current backend post to the local ledger; there is no external bank settlement integration.

History recognizes the backend journal prefixes `wallet:topup:`, `wallet:p2p:`, `wallet:withdraw:`, and `wallet:fx:` and includes the resulting credits/debits alongside recipient payments.

## Bruno setup

### Custom payment reasons

`POST /api/payments/draft` also accepts `purpose: "OTHERS"` with a required
`purposeReason` (1–250 nonblank characters, trimmed before storage). The reason
is included in payment detail/list responses and idempotency checks. Preset
purposes remain unchanged and ignore custom text. Migration V611 adds the
nullable `payments.purpose_reason` column without changing existing payments.

The signup verification step can be skipped to explore the dashboard. This does
not grant verified status: existing backend verification requirements still
apply. A customer reminder remains visible until documents are submitted.

| Setting | Value |
|---|---|
| Base URL variable | `http://localhost:8080` |
| Public endpoints | `/api/auth/register`, `/api/auth/login` |
| Authenticated header | `Authorization: Bearer {{token}}` |
| Admin header | `Authorization: Bearer {{adminToken}}` |
| JSON header | `Content-Type: application/json` |
| Optional tracing header | `X-Correlation-ID: bruno-test-001` |
| Required wallet/payment mutation header | `Idempotency-Key: {{$guid}}` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| OpenAPI JSON | `http://localhost:8080/v3/api-docs` |

Store the login response token as the Bruno environment variable `token`. Store IDs returned by earlier requests as `walletId`, `recipientId`, `paymentId`, `quoteId`, `routeId`, `kycApplicationId`, `policyDocumentId`, and `complianceCaseId`.

Successful responses normally use this envelope:

```json
{
  "correlationId": "bruno-test-001",
  "data": {}
}
```

## Authentication and profile APIs

| # | Method and URL | Auth / headers | Request body | Status and sample output |
|---:|---|---|---|---|
| 1 | `POST {{baseUrl}}/api/auth/register` | Public | `{"email":"user@fluxpay.test","password":"Pass123!","fullName":"Test User"}` | **201** `{"correlationId":"...","data":{"token":"<jwt>","tokenType":"Bearer","expiresIn":3600,"user":{"id":"<user-uuid>","email":"user@fluxpay.test","fullName":"Test User","role":"USER","kycStatus":"NONE"}}}` |
| 2 | `POST {{baseUrl}}/api/auth/login` | Public | `{"email":"alice@demo.io","password":"<SEED_CUSTOMER_PASSWORD>"}` | **200** `{"correlationId":"...","data":{"token":"<jwt>","tokenType":"Bearer","expiresIn":3600,"user":{"id":"<uuid>","email":"alice@demo.io","fullName":"Alice Demo","role":"USER","kycStatus":"VERIFIED"}}}` |
| 3 | `GET {{baseUrl}}/api/users/me` | Bearer token | None | **200** `{"correlationId":"...","data":{"id":"<uuid>","email":"alice@demo.io","fullName":"Alice Demo","role":"USER","kycStatus":"VERIFIED"}}` |
| 4 | `PUT {{baseUrl}}/api/users/me` | Bearer token | `{"fullName":"Alice Updated"}` | **200** `{"correlationId":"...","data":{"id":"<uuid>","email":"alice@demo.io","fullName":"Alice Updated","role":"USER","kycStatus":"VERIFIED"}}` |

## KYC APIs

| # | Method and URL | Auth / headers | Request body / parameters | Status and sample output |
|---:|---|---|---|---|
| 5 | `POST {{baseUrl}}/api/kyc/applications` | Bearer token; `multipart/form-data` | Fields `docType`, `docNumber`, and 1–4 repeated `files` parts containing actual PDF/JPG/PNG bytes | **201** application status plus `documents: [{id,fileName,fileType,fileSize,uploadedAt,available}]` |
| 6 | `GET {{baseUrl}}/api/kyc/my-status` | Bearer token | None | **200** `{"correlationId":"...","data":{"applicationId":"<uuid>","version":0,"status":"PENDING","rejectReason":null,"submittedAt":"2026-09-15T10:00:00Z","decidedAt":null}}` |
| 7 | `GET {{baseUrl}}/api/admin/kyc/applications?status=PENDING&page=0&size=50` | Admin token | Status: `NONE`, `PENDING`, `VERIFIED`, `REJECTED`, or `ALL`; size: `1-100` | **200** `{"correlationId":"...","data":[{"applicationId":"<uuid>","version":0,"email":"user@fluxpay.test","fullName":"Test User","docType":"PAN","docNumber":"ABCDE1234F","status":"PENDING","submittedAt":"2026-09-15T10:00:00Z","decidedAt":null,"rejectReason":null,"documents":[{"fileName":"pan.png","fileType":"image/png","fileSize":125000}]}]}` |
| 8 | `PUT {{baseUrl}}/api/admin/kyc/applications/{{kycApplicationId}}/approve` | Admin token | `{"expectedVersion":0,"reason":"Documents verified"}` | **200** `{"correlationId":"...","data":{"applicationId":"<uuid>","version":1,"status":"VERIFIED","rejectReason":null,"submittedAt":"2026-09-15T10:00:00Z","decidedAt":"2026-09-15T10:05:00Z"}}` |
| 9 | `PUT {{baseUrl}}/api/admin/kyc/applications/{{kycApplicationId}}/reject` | Admin token | `{"expectedVersion":0,"reason":"Document is unreadable"}` | **200** `{"correlationId":"...","data":{"applicationId":"<uuid>","version":1,"status":"REJECTED","rejectReason":"Document is unreadable","submittedAt":"2026-09-15T10:00:00Z","decidedAt":"2026-09-15T10:05:00Z"}}` |

KYC document types are `PASSPORT`, `AADHAAR`, `PAN`, and `DRIVING_LICENSE`.

Allowed file types are `application/pdf`, `image/jpeg`, and `image/png`. Maximum file size is 5 MB.

`GET /api/kyc/documents/{id}/content` returns the original binary only for the owning user or an administrator. Responses are non-cacheable attachments; the frontend fetches them with the bearer token and displays an in-memory popup. Neither filesystem paths nor bearer tokens are placed in document links. Missing/foreign documents return 404; unauthenticated requests return 401.

Real uploads are stored privately in `temp_images` (gitignored), using generated filenames. `scripts/start-backend.py` sets the workspace-root directory; override with `FLUXPAY_KYC_STORAGE_DIRECTORY`. This directory must remain writable and must not be exposed as static web content. It is persistent application storage despite its name: do not clear it while documents are needed. Keep backups of the database and this directory together.

Uploads are accepted only for an initial application or after rejection. Pending and verified applications cannot be overwritten. Admin decisions require the current `expectedVersion`; rejection requires a reason. Older metadata-only records have `available:false` and cannot be approved: reject pending legacy submissions with instructions to upload their originals. Existing verified users are not downgraded. On successful resubmission, replaced document files are removed after commit; rollback removes new files and preserves the old submission.

The legacy JSON metadata-only submission now returns 400 with instructions to upload actual files, even if the old development flag is enabled. Use multipart for all submissions. The user status and admin list now both include document IDs, upload timestamps and availability.

## Wallet and FX APIs

| # | Method and URL | Auth / headers | Request body / parameters | Status and sample output |
|---:|---|---|---|---|
| 10 | `GET {{baseUrl}}/api/wallets` | Bearer token | None | **200** `{"correlationId":"...","data":[{"walletId":"<wallet-uuid>","currency":"USD","heldBalance":"0.0000","availableBalance":"500.0000"}]}` |
| 11 | `GET {{baseUrl}}/api/wallets/{{walletId}}/ledger?page=0&size=20` | Bearer token | None | **200** `{"correlationId":"...","data":{"entries":[{"entryId":"<uuid>","entryType":"CREDIT","amount":"500.0000","currency":"USD","journalReference":"wallet:demo:<uuid>","narration":"Demo funding","createdAt":"2026-09-15T10:00:00Z"}],"page":0,"size":20,"totalElements":1,"totalPages":1}}` |
| 12 | `POST {{baseUrl}}/api/wallets/receive-demo` | Bearer + Idempotency-Key | `{"currency":"USD","amount":"500.0000"}` | **200** `{"correlationId":"...","data":{"walletId":"<uuid>","currency":"USD","balance":"500.0000","heldBalance":"0.0000","availableBalance":"500.0000","journalReference":"wallet:demo:<uuid>"}}` |
| 13 | `POST {{baseUrl}}/api/wallets/convert` | Bearer + Idempotency-Key | `{"from":"USD","to":"INR","amount":"100.0000"}` | **200** `{"correlationId":"...","data":{"sourceWalletId":"<uuid>","targetWalletId":"<uuid>","from":"USD","to":"INR","sourceAmount":"100.0000","fee":"0.5000","netAmount":"99.5000","creditedAmount":"8308.2500","rate":"83.50","rateFetchedAt":"2026-09-15T10:00:00Z","stale":false,"journalReference":"wallet:fx:<uuid>"}}` |
| 14 | `GET {{baseUrl}}/api/fx/rate?from=USD&to=INR` | Bearer token | Query parameters `from` and `to` | **200** `{"correlationId":"...","data":{"from":"USD","to":"INR","rate":"83.50","fetchedAt":"2026-09-15T10:00:00Z","stale":false}}` |

Supported currencies are `USD`, `EUR`, and `INR`. Send monetary amounts as JSON strings with no more than four decimal places.

## Recipient APIs

| # | Method and URL | Auth / headers | Request body | Status and sample output |
|---:|---|---|---|---|
| 15 | `POST {{baseUrl}}/api/recipients` | Bearer token | `{"name":"Rahul Sharma","account":"1234567890","bankName":"Demo Bank","country":"IN","currency":"INR","status":"ACTIVE"}` | **201** `{"correlationId":"...","data":{"id":"<recipient-uuid>","name":"Rahul Sharma","account":"1234567890","bankName":"Demo Bank","country":"IN","currency":"INR","status":"ACTIVE","version":0}}` |
| 16 | `GET {{baseUrl}}/api/recipients` | Bearer token | None | **200** `{"correlationId":"...","data":[{"id":"<recipient-uuid>","name":"Rahul Sharma","account":"1234567890","bankName":"Demo Bank","country":"IN","currency":"INR","status":"ACTIVE","version":0}]}` |
| 17 | `PUT {{baseUrl}}/api/recipients/{{recipientId}}` | Bearer token | `{"name":"Rahul Sharma Updated","account":"1234567890","bankName":"Demo Bank","country":"IN","currency":"INR","status":"ACTIVE","expectedVersion":0}` | **200** `{"correlationId":"...","data":{"id":"<recipient-uuid>","name":"Rahul Sharma Updated","account":"1234567890","bankName":"Demo Bank","country":"IN","currency":"INR","status":"ACTIVE","version":1}}` |

Recipient status values are `ACTIVE` and `BLOCKED`. The `country` field must be a two-letter code.

## Payment and quote APIs

| # | Method and URL | Auth / headers | Request body / parameters | Status and sample output |
|---:|---|---|---|---|
| 18 | `POST {{baseUrl}}/api/payments/draft` | Bearer + Idempotency-Key | `{"sourceWalletId":"{{walletId}}","recipientId":"{{recipientId}}","sourceAmount":"100.0000","sourceCurrency":"USD","payoutCurrency":"INR","purpose":"FAMILY_SUPPORT","preference":"BALANCED"}` | **201** `{"correlationId":"...","data":{"id":"<payment-uuid>","sourceWalletId":"<wallet-uuid>","recipientId":"<recipient-uuid>","sourceAmount":"100.0000","sourceCurrency":"USD","payoutCurrency":"INR","status":"DRAFT","selectedQuoteId":null,"createdAt":"2026-09-15T10:00:00Z"}}` |
| 19 | `POST {{baseUrl}}/api/payments/{{paymentId}}/quotes` | Bearer + Idempotency-Key | No body | **201** `{"correlationId":"...","data":{"paymentId":"<payment-uuid>","recommendedQuoteId":"<quote-uuid>","recommendationReason":"preference BALANCED","expiresAt":"2026-09-15T10:10:00Z","serverTime":"2026-09-15T10:00:00Z","quotes":[{"id":"<quote-uuid>","routeCode":"HDFC_INR_STANDARD","route":"HDFC_INR_STANDARD","routeId":"<route-uuid>","providerId":"<provider-uuid>","marketRate":"83.50","offeredRate":"83.0825","feeAmount":"1.0000","recipientAmount":"8225.1675","estimatedMinutes":60,"recommended":true,"effectiveReliability":"99.000000","rankingScore":"0.69491...","rankingPosition":1}]}}`. A generation persists at most the three top-ranked eligible quotes, and several winners may belong to the same provider. |
| 20 | `GET {{baseUrl}}/api/payments/{{paymentId}}/quotes` | Bearer token | None | **200** Same quote response shape as endpoint 19. |
| 21 | `POST {{baseUrl}}/api/payments/{{paymentId}}/confirm` | Bearer + Idempotency-Key | `{"quoteId":"{{quoteId}}"}` | **200** or **202** `{"correlationId":"...","data":{"id":"<payment-uuid>","sourceWalletId":"<wallet-uuid>","recipientId":"<recipient-uuid>","sourceAmount":"100.0000","sourceCurrency":"USD","payoutCurrency":"INR","status":"PROCESSING","selectedQuoteId":"<quote-uuid>","createdAt":"2026-09-15T10:00:00Z"}}` |
| 22 | `POST {{baseUrl}}/api/payments/{{paymentId}}/cancel` | Bearer + Idempotency-Key | No body | **200** `{"correlationId":"...","data":{"id":"<payment-uuid>","sourceWalletId":"<uuid>","recipientId":"<uuid>","sourceAmount":"100.0000","sourceCurrency":"USD","payoutCurrency":"INR","status":"CANCELLED","selectedQuoteId":null,"createdAt":"2026-09-15T10:00:00Z"}}` |
| 23 | `GET {{baseUrl}}/api/payments?page=0&size=20` | Bearer token | None | **200** `{"correlationId":"...","data":{"items":[{"id":"<payment-uuid>","sourceWalletId":"<uuid>","recipientId":"<uuid>","sourceAmount":"100.0000","sourceCurrency":"USD","payoutCurrency":"INR","status":"DRAFT","selectedQuoteId":null,"createdAt":"2026-09-15T10:00:00Z"}],"page":0,"size":20,"total":1}}` |
| 24 | `GET {{baseUrl}}/api/payments/{{paymentId}}` | Bearer token | None | **200** `{"correlationId":"...","data":{"id":"<payment-uuid>","sourceWalletId":"<uuid>","recipientId":"<uuid>","sourceAmount":"100.0000","sourceCurrency":"USD","payoutCurrency":"INR","status":"DRAFT","selectedQuoteId":null,"createdAt":"2026-09-15T10:00:00Z"}}` |

Payment purposes are `FAMILY_SUPPORT`, `EDUCATION`, `BUSINESS`, and `SAVINGS`.

Route preferences are `CHEAPEST`, `FASTEST`, and `BALANCED`.

Payment states are `DRAFT`, `QUOTED`, `UNDER_REVIEW`, `PROCESSING`, `COMPLETED`, `FAILED`, `REFUNDED`, `REJECTED`, and `CANCELLED`.

## Transfer routing admin APIs

Rail types are code-shipped: `INTERNAL_LEDGER` delivers to another FluxPay wallet while
`BANK_NETWORK`, `REAL_TIME_NETWORK`, and `PARTNER_NETWORK` deliver to external accounts. One
provider selects one rail type and owns many routes; many providers may share the same rail.
Administrators configure provider/route identity, eligibility, and commercials only — never
executable code, endpoints, scripts, credentials, or secrets. Codes are uppercase, unique, and
immutable; rail/provider bindings freeze after first use; used and system-protected records
archive instead of deleting. Every admin write requires Spring `ADMIN` authorization plus the
persistent role check.

| # | Method and URL | Auth / headers | Request body | Status and sample output |
|---:|---|---|---|---|
| 25 | `GET {{baseUrl}}/api/admin/rail-types` | Admin token | None | **200** `{"correlationId":"...","data":{"railTypes":[{"railType":"BANK_NETWORK","supportedDestinations":["EXTERNAL_ACCOUNT"]}]}}` |
| 26 | `GET {{baseUrl}}/api/admin/rail-types/BANK_NETWORK` | Admin token | None | **200** Same single-rail shape as endpoint 25. |
| 27 | `GET {{baseUrl}}/api/admin/providers` | Admin token | None | **200** `{"correlationId":"...","data":{"providers":[{"id":"<uuid>","providerCode":"HDFC_BANK","providerName":"HDFC Bank","railType":"BANK_NETWORK","active":true,"systemProtected":false,"archivedAt":null,"version":0}]}}` |
| 28 | `POST {{baseUrl}}/api/admin/providers` | Admin token | `{"providerCode":"HDFC_BANK","providerName":"HDFC Bank","railType":"BANK_NETWORK","active":true}` | **201** Same provider shape as endpoint 27. Duplicate codes return **409** `PROVIDER_CODE_CONFLICT`. |
| 29 | `PUT {{baseUrl}}/api/admin/providers/{{providerId}}` | Admin token | `{"providerName":"HDFC Bank","railType":"BANK_NETWORK","active":true,"version":0}` | **200** Same provider shape as endpoint 27 with `version:1`. Stale versions return **409** `STALE_PROVIDER`; a used binding change returns **409** `ROUTING_BINDING_IMMUTABLE`. |
| 30 | `DELETE {{baseUrl}}/api/admin/providers/{{providerId}}?version=0` | Admin token | None | **200** `{"correlationId":"...","data":{"disposition":"DELETED"}}` (unused, non-system) or `{"disposition":"ARCHIVED"}` (used or system-protected). Providers with child routes return **409** `PROVIDER_HAS_ROUTES`. |
| 31 | `GET {{baseUrl}}/api/admin/routes` | Admin token | None | **200** `{"correlationId":"...","data":{"routes":[{"id":"<uuid>","providerId":"<uuid>","routeCode":"HDFC_INR_STANDARD","name":"HDFC INR Standard","destinationType":"EXTERNAL_ACCOUNT","destinationCountry":"IN","payoutCurrency":"INR","baseFee":"1.0000","fxSpreadPercentage":"0.500000","estimatedMinutes":60,"configuredSuccessRate":"99.00","effectiveSuccessRate":"99.000000","completedCount":0,"failedCount":0,"minimumRecipientAmount":null,"maximumRecipientAmount":null,"active":true,"systemProtected":false,"archivedAt":null,"version":0}]}}` |
| 32 | `POST {{baseUrl}}/api/admin/routes` | Admin token | `{"providerId":"<uuid>","routeCode":"HDFC_INR_STANDARD","name":"HDFC INR Standard","destinationType":"EXTERNAL_ACCOUNT","destinationCountry":"IN","payoutCurrency":"INR","baseFee":1,"fxSpreadPercentage":0.5,"estimatedMinutes":60,"configuredSuccessRate":99,"minimumRecipientAmount":null,"maximumRecipientAmount":null,"active":true}` | **201** Same route shape as endpoint 31. Incompatible rail/destination returns **400** `INVALID_TRANSFER_ROUTE`; duplicate codes return **409** `ROUTE_CODE_CONFLICT`. |
| 33 | `PUT {{baseUrl}}/api/admin/routes/{{routeId}}` | Admin token | Same fields as endpoint 32 minus `routeCode`, plus `version`. | **200** Same route shape as endpoint 31 with a bumped `version`. Stale versions return **409** `STALE_ROUTE`. |
| 34 | `DELETE {{baseUrl}}/api/admin/routes/{{routeId}}?version=0` | Admin token | None | **200** Same disposition shape as endpoint 30. |
| 35 | `GET {{baseUrl}}/api/routes` | Bearer token | None | **200** `{"correlationId":"...","data":{"routes":[{"routeId":"<uuid>","routeCode":"HDFC_INR_STANDARD","routeName":"HDFC INR Standard","providerName":"HDFC Bank","routeType":"BANK_NETWORK","baseFee":"1.0000","fxSpreadPercentage":"0.500000","estimatedMinutes":60,"successRate":"99.00","active":true,"version":0,"successCount":0,"totalAttempts":0}]}}` (informational catalogue; quoting and execution never trust it). |
| 36 | `POST {{baseUrl}}/api/payments/{{paymentId}}/recommend-route` | Bearer token; owner only | `{"preference":"BALANCED"}`. Body may be omitted; the default is `BALANCED`. | **200** `{"correlationId":"...","data":{"paymentId":"<payment-uuid>","recommendedRouteId":"<route-uuid>","recommendationReason":"BALANCED selected HDFC_INR_STANDARD ... showing top 3.","quotes":[{"routeId":"<route-uuid>","routeCode":"HDFC_INR_STANDARD","routeName":"HDFC INR Standard","providerId":"<provider-uuid>","providerName":"HDFC Bank","marketRate":83.50,"offeredRate":83.0825,"feeAmount":1.0000,"recipientAmount":8225.1675,"estimatedMinutes":60,"effectiveReliability":99.000000,"rankingScore":0.6949...,"rankingPosition":1,"recommended":true}]}}` |

Seeded catalogue: protected `FLUXPAY` (`INTERNAL_LEDGER`) with wallet routes in `INR`/`USD`/`EUR`,
plus one initially inactive provider per external rail (`BANK_ALPHA`, `REAL_TIME`, `PARTNER`) with
representative `IN`/`INR`, `US`/`USD`, and `DE`/`EUR` routes. For the local payment-operations
demonstration, the selected seeded `BANK_STANDARD` and `BANK_EXPRESS` routes are activated by
`scripts/seed-local.py` when the development simulation policy is enabled; the seed validates
all selected rows first and changes no unrelated active flags. A normal seed with no demo policy
is insert-only.

Route preferences are `CHEAPEST`, `FASTEST`, and `BALANCED`. Eligibility keeps routes whose
provider and route are active and unarchived, whose destination corridor matches, and whose rail
is installed and compatible. Learned reliability blends the configured success rate (a 20-attempt
prior) with terminal `COMPLETED`/`FAILED` outcomes; processing or uncertain outcomes are ignored.

## Payout, recovery, and timeline APIs

| # | Method and URL | Auth / headers | Request body | Status and sample output |
|---:|---|---|---|---|
| 28 | `POST {{baseUrl}}/api/payments/{{paymentId}}/submit-payout` | Bearer + Idempotency-Key; owner only | `{"routeCode":"HDFC_INR_STANDARD"}` | **200** `{"correlationId":"...","data":{"attemptNumber":1,"routeCode":"HDFC_INR_STANDARD","status":"COMPLETED","providerRef":"provider-123","error":null,"allowed":[],"alreadyConfirmed":false,"originalEventId":"<event-uuid>","selectedQuote":{"quoteId":"<quote-uuid>","routeCode":"HDFC_INR_STANDARD","feeAmount":5.0000,"netSourceAmount":95.0000,"offeredRate":83.0825,"recipientAmount":7892.8375}}}` |
| 29 | `POST {{baseUrl}}/api/payments/{{paymentId}}/retry-payout` | Bearer + Idempotency-Key; owner only | `{"quoteId":"{{quoteId}}"}`; body is technically optional | **200** `{"correlationId":"...","data":{"attemptNumber":2,"routeCode":"HDFC_INR_STANDARD","status":"COMPLETED","providerRef":"provider-456","error":null,"allowed":[],"alreadyConfirmed":false,"originalEventId":"<event-uuid>","selectedQuote":{"quoteId":"<quote-uuid>","routeCode":"HDFC_INR_STANDARD","feeAmount":5.0000,"netSourceAmount":95.0000,"offeredRate":83.0825,"recipientAmount":7892.8375}}}` |
| 30 | `POST {{baseUrl}}/api/payments/{{paymentId}}/switch-route` | Bearer + Idempotency-Key; owner only | `{"routeCode":"SBI_INR_STANDARD","quoteId":"{{quoteId}}"}` | **200** `{"correlationId":"...","data":{"attemptNumber":2,"routeCode":"SBI_INR_STANDARD","status":"COMPLETED","providerRef":"provider-789","error":null,"allowed":[],"alreadyConfirmed":false,"originalEventId":"<event-uuid>","selectedQuote":{"quoteId":"<quote-uuid>","routeCode":"SBI_INR_STANDARD","feeAmount":2.0000,"netSourceAmount":98.0000,"offeredRate":83.29125,"recipientAmount":8162.5425}}}` |
| 31 | `POST {{baseUrl}}/api/payments/{{paymentId}}/refund` | Bearer token | No body | **403** `{"error":{"code":"FORBIDDEN","message":"Refunds require an administrator"}}`. The customer path is intentionally unavailable; automatic `payout.refund` recovery and `POST /api/admin/payments/{{paymentId}}/refund` (admin + `Idempotency-Key`) are the supported refund paths. |
| 32 | `GET {{baseUrl}}/api/payments/{{paymentId}}/timeline` | Bearer token; owner only | None | **200** `{"correlationId":"...","data":[{"eventId":"<event-uuid>","paymentId":"<payment-uuid>","eventType":"payment.initiated","kafkaTopic":"payment.initiated","correlationId":"bruno-test-001","payload":{"schemaVersion":1,"aggregateSequence":1},"occurredAt":"2026-09-15T10:00:00Z"}]}` |

## Payment operations admin API

The following endpoint is an `ADMIN`-only, read-only aggregate over persisted payment
state. It has no POST, PUT, PATCH, DELETE, simulation, retry, refund, or reconciliation
write mapping. The customer owner timeline remains the separate owner-only API above.

```http
GET /api/admin/payments/{paymentId}/operations
Authorization: Bearer <admin-token>
```

| Method and URL | Auth / headers | Request | Status and behavior |
|---|---|---|---|
| `GET {{baseUrl}}/api/admin/payments/{{paymentId}}/operations` | `ADMIN` bearer token; no request body and no `Idempotency-Key` | None | **200** returns one correlated evidence snapshot; malformed or unknown payment IDs return the standard **404** envelope; unauthenticated/non-admin requests return **401**/**403** |

A successful response has the standard `{correlationId,data}` envelope. The `data`
object contains ordered `payment`, `attempts`, `outboxEvents`, `timelineEvents`,
`operations`, `ledgerEntries`, and `recovery` fields. Outbox entries include the event
ID/type, aggregate sequence, payload, and delivery state/attempt metadata. Timeline
entries include the exact event ID, Kafka topic, correlation ID, payload, and
occurrence time. Recovery entries expose the persisted decision and automated retry
count; refund ledger entries expose the original funding and reversal journals.

```json
{
  "correlationId": "bruno-test-001",
  "data": {
    "payment": {
      "id": "<payment-uuid>",
      "status": "PROCESSING",
      "selectedQuoteId": "<quote-uuid>",
      "eventSequence": 4,
      "createdAt": "2026-09-24T10:00:00Z",
      "updatedAt": "2026-09-24T10:00:05Z"
    },
    "attempts": [
      {
        "id": "<attempt-uuid>",
        "attemptNumber": 1,
        "status": "FAILED",
        "routeCode": "BANK_STANDARD",
        "providerCode": "BANK_ALPHA",
        "providerReference": null,
        "errorCode": "SIMULATED_PROVIDER_FAILURE",
        "errorMessage": "Simulated definitive failure for BANK_STANDARD",
        "initiatedAt": "2026-09-24T10:00:00Z",
        "completedAt": "2026-09-24T10:00:01Z"
      }
    ],
    "outboxEvents": [
      {
        "eventId": "<event-uuid>",
        "eventType": "payout.failed",
        "aggregateSequence": 4,
        "createdAt": "2026-09-24T10:00:01Z",
        "payload": {"attempt": 1},
        "delivery": {
          "state": "SENT",
          "attemptCount": 0,
          "nextAttemptAt": "2026-09-24T10:00:01Z",
          "sentAt": "2026-09-24T10:00:01Z",
          "lastError": null
        }
      }
    ],
    "timelineEvents": [
      {
        "eventId": "<event-uuid>",
        "eventType": "payout.failed",
        "kafkaTopic": "payout.failed",
        "correlationId": "<correlation-uuid>",
        "payload": {"attempt": 1},
        "occurredAt": "2026-09-24T10:00:01Z"
      }
    ],
    "operations": [],
    "ledgerEntries": [],
    "recovery": {
      "automatedRetryCount": 0,
      "decision": "RETRY_SCHEDULED",
      "nextRun": "2026-09-24T10:00:06Z"
    }
  }
}
```

For the two-payment local demonstration, use `BANK_STANDARD` for `retry-success` and
`BANK_EXPRESS` for `refund-exhaustion` with the exact variables documented in the
[README payment-operations runbook](../README.md#payment-operations-event-flow-demonstration).
The selected seeded routes are activated by `scripts/seed-local.py` only when the
local simulation policy is enabled; administrators do not manually activate them for
this run. The full flow, evidence distinctions, and presenter timing are maintained
in that runbook.

## Admin statistics reporting API

Three `ADMIN`-only, read-only GET endpoints share the `/api/admin/reports` base path
with the legacy provider summary below. They report payments **created** in the
selected period together with their **current** outcomes. They are not a historical
snapshot of statuses at period end and not a chart of settlement dates, so a past
period's figures can change when a payment completes, retries, or is refunded. Read
the `meta` block to see the exact bounds that produced a response. None of these
endpoints has a POST, PUT, PATCH, or DELETE mapping and none uses `Idempotency-Key`.

```http
GET /api/admin/reports/statistics?from=2026-09-01&to=2026-09-25&currency=INR
Authorization: Bearer <admin-token>

GET /api/admin/reports/statistics/payments?from=2026-09-18&to=2026-09-18&currency=INR&status=FAILED&page=0&size=20
Authorization: Bearer <admin-token>
```

| Method and URL | Auth / headers | Request query | Status and behavior |
|---|---|---|---|
| `GET {{baseUrl}}/api/admin/reports/statistics/options` | `ADMIN` bearer token; no request body | None | **200** returns `currencies`, `defaultCurrency`, `reportingZone`, `today`, and `maximumRangeDays`; it takes no filter, so it has no query-parameter failure mode |
| `GET {{baseUrl}}/api/admin/reports/statistics` | `ADMIN` bearer token; no request body | `from`, `to`, `currency` | **200** returns `meta`, `paymentSummary`, `paymentTrend`, `paymentStatuses`, `providers`, `customers`, `customerTrend`, and `workload`; invalid filters return the **400** codes below |
| `GET {{baseUrl}}/api/admin/reports/statistics/payments` | `ADMIN` bearer token; no request body | `from`, `to`, `currency`, optional `status`, `page`, `size` | **200** returns one page of cohort payment rows; invalid filters return the **400** codes below |

Unauthenticated requests return **401**; authenticated non-administrators return the
standard **403** `FORBIDDEN` envelope. The administrator-facing usage guide for these
endpoints is [docs/admin-statistics.md](admin-statistics.md).

### Dates, currency, and effective bounds

`from` and `to` are **inclusive calendar dates in `Asia/Kolkata` (IST)** in
`YYYY-MM-DD` form. The service converts them to the half-open instant window
`[start of from, start of the day after to)` in that zone, and the response returns
that window as `meta.fromInclusive` and `meta.toExclusive`. `from <= to` is required,
the range may not end on a future date, and at most **366 inclusive** calendar days are
allowed.

The effective query end is capped at the request's `meta.generatedAt`, and that capped
instant is what `meta.toExclusive` reports. Selecting today therefore covers only the
elapsed part of today; a partial final day is expected, not a defect. `meta.periodBasis`
is the fixed string `PAYMENT_CREATED_AT`, and `meta.currencyScale` carries the selected
currency's configured precision. Amounts are transported as decimal strings, so no
rounding is introduced by JSON transport.

`currency` is the **source currency** (`payments.currency`) and is required. It is not
an exchange rate or a converted total: there is deliberately **no "all currencies"
monetary figure and no exchange-rate conversion** anywhere in this API. Options come
from the canonical currency configuration, `defaultCurrency` is `INR` when that
currency is configured (otherwise the first code alphabetically) and is `null` when no
currency is configured at all.

### Payment metrics

All payment metrics share one cohort: payments whose creation instant falls inside the
effective window and whose source currency matches. Payment rows are never joined to
attempts in a way that could multiply them.

| Field | Meaning |
|---|---|
| `paymentSummary.paymentCount` | Every payment in the cohort, including `DRAFT` and `QUOTED` |
| `paymentSummary.completedCount` / `completedAmount` | Payments currently `COMPLETED`, and the sum of their source amounts |
| `paymentSummary.payoutSuccessRate` | `100 * COMPLETED / (COMPLETED + FAILED + REFUNDED)` |
| `paymentSummary.failedCount` | Payments currently `FAILED`; `REFUNDED` payments have their own status and are not counted here |
| `paymentSummary.processingCount` | Payments currently `PROCESSING`, including uncertain delivery awaiting reconciliation |
| `paymentTrend[]` | One zero-filled entry per selected date with `paymentCount` and that day's `completedAmount` |
| `paymentStatuses[]` | One `count` for each of the nine payment statuses, including zero-valued statuses |

`payoutSuccessRate` is `null` — not `0` and not `100` — when no payment in the cohort
has a final outcome, and the admin page renders that as **"No final outcomes"**. Rates
are rounded to two decimal places on the server. Daily completed amount is grouped by
payment **creation** date, not completion date. A payment that recovered after a retry
counts once, in its current state.

### Provider rows are attempts

`providers[]` counts **payout attempts**, not payments, and follows
`payout_attempts.transfer_route_id -> transfer_routes.provider_id -> transfer_providers.id`.
It includes attempts belonging to the selected payment cohort that were initiated before
`meta.generatedAt`, **even when the attempt happened after the selected creation period**,
so read the table as "attempts for payments created in the selected period". Archived
and inactive providers appear whenever they have matching history, and rows are ordered
by `totalAttempts` descending, then `providerCode`.

`successRate` is `completedAttempts / (completedAttempts + failedAttempts)`. `INITIATED`
and `PROCESSING` attempts are reported as `inProgressAttempts` and are excluded from
that denominator, so the rate is `null` — shown as **"No terminal attempts"** — when a
provider has no completed or failed attempt. Attempt totals are deliberately distinct
from payment totals, and provider cells are not payment drill-downs.

### Customers and current workload

`customers` counts role `USER` accounts only; `ADMIN` and `SYSTEM` accounts are excluded.
`customers.totalCustomers` is an all-time count of accounts created before
`meta.generatedAt`, while `customers.newRegistrations` and `customerTrend[]` follow the
selected dates. Both are **currency-independent** — changing the source currency does not
change either customer figure.

`workload` is a current snapshot across **all dates and all currencies**; the selected
range and currency do not narrow it. `kycPending` counts `PENDING` KYC applications and
`kycOver24h` the subset older than 24 hours, aged from `submitted_at`. `complianceOpen`
counts `OPEN` cases, `complianceHighRisk` the `OPEN` `HIGH`-risk subset, and
`complianceOver24h` the `OPEN` cases aged from `created_at`. `ticketsOpen` counts `OPEN`
or `IN_PROGRESS` support tickets and `ticketsOver24h` those same unresolved tickets aged
from `created_at`. "Over 24 hours" means strictly before `meta.generatedAt` minus 24
hours; it is an age count, not an SLA compliance claim.

### Sample responses

```json
{
  "correlationId": "bruno-test-001",
  "data": {
    "currencies": [{ "code": "INR", "scale": 2 }, { "code": "USD", "scale": 2 }],
    "defaultCurrency": "INR",
    "reportingZone": "Asia/Kolkata",
    "today": "2026-09-25",
    "maximumRangeDays": 366
  }
}
```

```json
{
  "correlationId": "bruno-test-001",
  "data": {
    "meta": {
      "from": "2026-09-01",
      "to": "2026-09-25",
      "currency": "INR",
      "currencyScale": 2,
      "reportingZone": "Asia/Kolkata",
      "fromInclusive": "2026-08-31T18:30:00Z",
      "toExclusive": "2026-09-25T10:00:00Z",
      "generatedAt": "2026-09-25T10:00:00Z",
      "periodBasis": "PAYMENT_CREATED_AT"
    },
    "paymentSummary": {
      "paymentCount": 42,
      "completedCount": 30,
      "completedAmount": "12500.00",
      "payoutSuccessRate": 93.75,
      "failedCount": 2,
      "processingCount": 1
    },
    "paymentTrend": [
      { "date": "2026-09-01", "paymentCount": 2, "completedAmount": "500.00" },
      { "date": "2026-09-02", "paymentCount": 0, "completedAmount": "0.00" }
    ],
    "paymentStatuses": [
      { "status": "DRAFT", "count": 4 },
      { "status": "QUOTED", "count": 2 },
      { "status": "UNDER_REVIEW", "count": 1 },
      { "status": "PROCESSING", "count": 1 },
      { "status": "COMPLETED", "count": 30 },
      { "status": "FAILED", "count": 2 },
      { "status": "REFUNDED", "count": 0 },
      { "status": "REJECTED", "count": 1 },
      { "status": "CANCELLED", "count": 1 }
    ],
    "providers": [
      {
        "providerId": "<provider-uuid>",
        "providerCode": "BANK_ALPHA",
        "providerName": "Bank Alpha",
        "totalAttempts": 33,
        "completedAttempts": 30,
        "failedAttempts": 3,
        "inProgressAttempts": 0,
        "successRate": 90.91
      }
    ],
    "customers": { "totalCustomers": 318, "newRegistrations": 12 },
    "customerTrend": [{ "date": "2026-09-01", "registrations": 1 }],
    "workload": {
      "kycPending": 4,
      "kycOver24h": 2,
      "complianceOpen": 3,
      "complianceHighRisk": 1,
      "complianceOver24h": 1,
      "ticketsOpen": 5,
      "ticketsOver24h": 3
    }
  }
}
```

`paymentTrend` and `customerTrend` carry exactly one entry per selected date, zero
filled, in the examples above truncated after the first rows. The status counts in the
example sum to `paymentSummary.paymentCount`.

### Payment list and paging

```json
{
  "correlationId": "bruno-test-001",
  "data": {
    "meta": {
      "from": "2026-09-18",
      "to": "2026-09-18",
      "currency": "INR",
      "currencyScale": 2,
      "reportingZone": "Asia/Kolkata",
      "fromInclusive": "2026-09-17T18:30:00Z",
      "toExclusive": "2026-09-18T18:30:00Z",
      "generatedAt": "2026-09-19T10:00:00Z",
      "periodBasis": "PAYMENT_CREATED_AT"
    },
    "status": "FAILED",
    "page": 0,
    "size": 20,
    "totalElements": 2,
    "totalPages": 1,
    "items": [
      {
        "paymentId": "<payment-uuid>",
        "createdAt": "2026-09-18T04:15:00Z",
        "sourceAmount": "25.00",
        "sourceCurrency": "INR",
        "status": "FAILED"
      }
    ]
  }
}
```

`page` is **zero-based** and defaults to `0`; `size` defaults to `20` and the server
maximum is `100`. Rows are ordered deterministically by `created_at DESC, id DESC`, so
repeated requests for one page do not reshuffle. Omit `status` to list every status in
the cohort; a supplied `status` uses the existing payment status enum. The list query
and the dashboard's counts share the same cohort and status predicates.

Requesting a page beyond the end of the result set is a **successful empty response** with
truthful `totalElements`/`totalPages`, not an error. A day drill-down sends that single
day as both `from` and `to`; the admin page keeps its broader dates in its own page
state while the list request is narrowed. The list and the dashboard are separate
reads, so a payment that changes state between them can make a list total differ
slightly from the card that opened it.

### Reporting error codes

| Situation | Status | Sample output |
|---|---:|---|
| Missing or malformed `from`/`to` | 400 | `{"correlationId":"...","code":"INVALID_REPORT_RANGE","message":"Use valid YYYY-MM-DD report dates.","fieldErrors":{},"ts":"..."}` |
| `from` after `to`, more than 366 inclusive days, or a future end date | 400 | `{"correlationId":"...","code":"INVALID_REPORT_RANGE","message":"Select up to 366 days ending no later than today.","fieldErrors":{},"ts":"..."}` |
| Missing or unsupported `currency` | 400 | `{"correlationId":"...","code":"INVALID_REPORT_CURRENCY","message":"Select a supported report currency.","fieldErrors":{},"ts":"..."}` |
| Unrecognized `status` | 400 | `{"correlationId":"...","code":"INVALID_REPORT_STATUS","message":"Select a valid payment status.","fieldErrors":{},"ts":"..."}` |
| Non-numeric, negative, or oversized `page`/`size` | 400 | `{"correlationId":"...","code":"INVALID_REPORT_PAGE","message":"Page must be non-negative and size must be from 1 to 100.","fieldErrors":{},"ts":"..."}` |
| Reporting currency configuration is missing, duplicated, or has an out-of-range scale | 500 | `{"correlationId":"...","code":"INVALID_REPORT_CONFIGURATION","message":"The reporting currency configuration is invalid.","fieldErrors":{},"ts":"..."}` |

`INVALID_REPORT_RANGE` is the one code here that carries two different messages: the date
parser rejects a missing or unparseable date, while the range check rejects a
well-formed but unusable range. Match on `code`, not on `message`.

### Relationship to the legacy provider summary

`GET /api/admin/reports/provider-summary` remains available with **unchanged behavior**.
It takes ISO-8601 **instants** (not calendar dates) in `from`/`to`, rejects a range whose
start is not before its end with `INVALID_REPORT_RANGE`, has no currency filter and no
payment-cohort filter, counts attempts **initiated inside its own range**, groups by
provider **name**, and includes every provider even when it has zero matching attempts.
It has no `meta` block and no in-progress attempt column.

The difference is deliberate. Use `provider-summary` when you want "attempts started
between two instants, all providers". Use the statistics `providers` panel when you want
"attempts belonging to the payments created in this period and currency", which may
include attempts that started outside the period.

### Out of scope

The statistics API does not report wallet top-ups, direct wallet transfers, exchanges,
or withdrawals; accounting revenue or profit; exchange-rate conversion; forecasts;
historical queue snapshots; SLA compliance; exports; or automatic refresh. It is
read-only and adds no caches, materialized views, Kafka consumers, events, or tables.

## Policy APIs

| # | Method and URL | Auth / headers | Request body | Status and sample output |
|---:|---|---|---|---|
| 33 | `POST {{baseUrl}}/api/policies` | Bearer token | `{"title":"High-value payment review","category":"PAYMENT_REVIEW","content":"Payments above the configured threshold require compliance review."}` | **201** `{"correlationId":"...","data":{"id":"<policy-uuid>","title":"High-value payment review","category":"PAYMENT_REVIEW","content":"Payments above the configured threshold require compliance review.","documentHash":"<sha-256>","createdAt":"2026-09-15T10:00:00Z","chunks":[]}}` |
| 34 | `GET {{baseUrl}}/api/policies` | Bearer token | None | **200** `{"correlationId":"...","data":[{"id":"<policy-uuid>","title":"High-value payment review","category":"PAYMENT_REVIEW","content":"Payments above the configured threshold require compliance review.","documentHash":"<sha-256>","createdAt":"2026-09-15T10:00:00Z","chunks":[]}]}` |
| 35 | `GET {{baseUrl}}/api/policies/{{policyDocumentId}}` | Bearer token | None | **200** Same policy document response shape as endpoint 33, including its `chunks` array. |
| 36 | `POST {{baseUrl}}/api/policies/{{policyDocumentId}}/chunks` | Bearer token | `{"content":"Payments above the configured threshold require compliance review."}` | **201** `{"correlationId":"...","data":{"id":"<chunk-uuid>","policyDocumentId":"<policy-uuid>","chunkNumber":1,"content":"Payments above the configured threshold require compliance review.","createdAt":"2026-09-15T10:01:00Z"}}` |
| 37 | `GET {{baseUrl}}/api/policies/{{policyDocumentId}}/chunks` | Bearer token | None | **200** `{"correlationId":"...","data":[{"id":"<chunk-uuid>","policyDocumentId":"<policy-uuid>","chunkNumber":1,"content":"Payments above the configured threshold require compliance review.","createdAt":"2026-09-15T10:01:00Z"}]}` |
| 38 | `POST {{baseUrl}}/api/policies/{{policyDocumentId}}/index` | Bearer token | No body | **200** `{"correlationId":"...","data":{"policyDocumentId":"<policy-uuid>","chunkCount":1}}` |
| 39 | `DELETE {{baseUrl}}/api/policies/{{policyDocumentId}}` | Admin token | None | **204** No content. |

Policy categories are `KYC`, `AML`, `PAYMENT_REVIEW`, `COUNTRY_RULE`, and `SUPPORT`.

Policy titles are limited to 200 characters. Creating a policy with the same title and content as an existing policy returns **409**. Indexing rebuilds vector chunks from the policy document content and publishes a new active vector generation.

## Compliance case APIs

| # | Method and URL | Auth / headers | Request body / parameters | Status and sample output |
|---:|---|---|---|---|
| 40 | `POST {{baseUrl}}/api/compliance/cases` | Bearer token | `{"paymentId":"{{paymentId}}","risk":"HIGH","riskReasons":["High-value payment"],"suggestedAction":"Review source of funds"}` | **201** `{"correlationId":"...","data":{"id":"<case-uuid>","paymentId":"<payment-uuid>","reviewReference":null,"risk":"HIGH","status":"OPEN","riskReasons":["High-value payment"],"suggestedAction":"Review source of funds","decidedBy":null,"decidedAt":null,"decisionReason":null,"createdAt":"2026-09-15T10:00:00Z"}}` |
| 41 | `GET {{baseUrl}}/api/compliance/cases?status=OPEN` | Bearer token | Optional `status` query parameter | **200** `{"correlationId":"...","data":[{"id":"<case-uuid>","paymentId":"<payment-uuid>","reviewReference":null,"risk":"HIGH","status":"OPEN","riskReasons":["High-value payment"],"suggestedAction":"Review source of funds","decidedBy":null,"decidedAt":null,"decisionReason":null,"createdAt":"2026-09-15T10:00:00Z"}]}` |
| 42 | `GET {{baseUrl}}/api/compliance/cases/{{complianceCaseId}}` | Bearer token | None | **200** Same compliance case response shape as endpoint 40. |
| 43 | `PUT {{baseUrl}}/api/compliance/cases/{{complianceCaseId}}/approve` | Admin token | `{"decisionReason":"Source of funds verified"}` | **200** `{"correlationId":"...","data":{"id":"<case-uuid>","paymentId":"<payment-uuid>","reviewReference":null,"risk":"HIGH","status":"APPROVED","riskReasons":["High-value payment"],"suggestedAction":"Review source of funds","decidedBy":"admin@local.fluxpay","decidedAt":"2026-09-15T10:05:00Z","decisionReason":"Source of funds verified","createdAt":"2026-09-15T10:00:00Z"}}` |
| 44 | `PUT {{baseUrl}}/api/compliance/cases/{{complianceCaseId}}/reject` | Admin token | `{"decisionReason":"Source of funds could not be verified"}` | **200** Same response shape as endpoint 43 with status `REJECTED`. |
| 45 | `DELETE {{baseUrl}}/api/compliance/cases/{{complianceCaseId}}` | Admin token | None | **204** No content. Only an open, manually created case can be deleted. |

Compliance risk values are `LOW`, `MEDIUM`, and `HIGH`. Case status values are `OPEN`, `APPROVED`, `REJECTED`, and `CLOSED`.

The decision reason is optional and limited to 500 characters. The server records `decidedBy` from the authenticated administrator rather than trusting a value supplied in the request. Approving or rejecting a case created by the automated payment-review workflow also advances or rejects its linked payment; a manually created case has no `reviewReference` and does not change payment state.

## Compliance Copilot API

| # | Method and URL | Auth / headers | Request body | Status and sample output |
|---:|---|---|---|---|
| 46 | `POST {{baseUrl}}/api/copilot/ask` | Admin token | `{"question":"When does a payment require manual review?","paymentId":"{{paymentId}}"}`. `paymentId` is optional. | **200** `{"correlationId":"...","data":{"answer":"Payments above the configured threshold require compliance review.","sources":[{"policyDocumentId":"<policy-uuid>","title":"High-value payment review","chunkNumber":1,"excerpt":"Payments above the configured threshold require compliance review."}]}}` |

The copilot returns an empty `sources` array and a scoped fallback answer when no indexed policy is relevant enough to answer the question.

## Important runtime conditions

| Feature | Default behavior | Setting needed for successful local testing |
|---|---|---|
| Demo wallet funding | Returns **404 NOT_FOUND** | `FLUXPAY_DEMO_FUNDING_ENABLED=true` |
| KYC document upload | Real multipart upload into private local storage | Writable `temp_images` or `FLUXPAY_KYC_STORAGE_DIRECTORY`; no metadata flag required |
| Compliance confirmation | Real provider is unavailable | `FLUXPAY_DEVELOPMENT_SIMULATED_COMPLIANCE_ENABLED=true` |
| Policy indexing | Requires Ollama embeddings and Oracle vector storage | Configure the `FLUXPAY_OLLAMA_*` and `FLUXPAY_POLICY_CHUNKER_VERSION` settings |
| Compliance Copilot | Requires an indexed policy corpus, Ollama embeddings/chat, and Oracle vector search | Index at least one policy and configure the `FLUXPAY_OLLAMA_*` and `FLUXPAY_COPILOT_*` settings |
| Payout submission | Real payout provider is unavailable | `FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true`; with the demo policy configured, `scripts/seed-local.py` validates and activates only the selected seeded `BANK_STANDARD`/`BANK_EXPRESS` routes and their `BANK_ALPHA` provider. With no demo policy, the seed is insert-only and active flags are unchanged |
| FX calls | Depend on the configured HTTP FX provider | Configure `FX_PROVIDER_URL` and network access |
| Draft payment | User must be KYC verified | Approve KYC using an admin token first |
| Draft payment | Wallet must have funds | Call `receive-demo` before drafting |
| Timeline | Kafka/outbox processing is asynchronous | Allow time for the consumer to persist events |

## Common error output

| Situation | Status | Sample output |
|---|---:|---|
| Missing or invalid token | 401 | `{"correlationId":"...","code":"AUTH_REQUIRED","message":"authentication is required","fieldErrors":{},"ts":"2026-09-15T10:00:00Z"}` |
| Non-admin or non-owner | 403 | `{"correlationId":"...","code":"FORBIDDEN","message":"access is forbidden","fieldErrors":{},"ts":"..."}` |
| Validation failure | 400 | `{"correlationId":"...","code":"VALIDATION","message":"validation failed","fieldErrors":{"email":"must be a well-formed email address"},"ts":"..."}` |
| Missing Idempotency-Key | 400 | `{"correlationId":"...","code":"INVALID_IDEMPOTENCY_KEY","message":"Idempotency-Key is required","fieldErrors":{},"ts":"..."}` |
| Resource not found | 404 | `{"correlationId":"...","code":"NOT_FOUND","message":"Resource not found","fieldErrors":{},"ts":"..."}` |
| Version or idempotency conflict | 409 | `{"correlationId":"...","code":"CONFLICT","message":"The resource was changed","fieldErrors":{},"ts":"..."}` |
| Insufficient wallet funds | 422 | `{"correlationId":"...","code":"INSUFFICIENT_FUNDS","message":"Insufficient wallet funds","fieldErrors":{},"ts":"..."}` |
| Expired quote | 412 | `{"correlationId":"...","code":"QUOTE_EXPIRED","message":"Quote has expired","fieldErrors":{},"ts":"..."}` |
| Missing external provider | 503 | `{"correlationId":"...","code":"FX_UNAVAILABLE","message":"FX provider is unavailable","fieldErrors":{},"ts":"..."}` |
| Copilot provider unavailable | 503 | `{"correlationId":"...","code":"COPILOT_UNAVAILABLE","message":"Compliance Copilot is temporarily unavailable.","fieldErrors":{},"ts":"..."}` |

## Recommended Bruno test order

| Step | Request | Value to save |
|---:|---|---|
| 1 | Register or log in as a customer | `token` |
| 2 | Log in as the seeded administrator | `adminToken` |
| 3 | List rail types, then create providers and routes (for example HDFC/SBI sharing `BANK_NETWORK`) | `providerId`, `routeId` |
| 4 | Submit and approve KYC | `kycApplicationId` |
| 5 | Receive demo funds and list wallets | `walletId` |
| 6 | Create a recipient | `recipientId` |
| 7 | Create a draft payment | `paymentId` |
| 8 | Create payment quotes (at most the top three eligible routes) | `quoteId` |
| 9 | Confirm the quote | None |
| 10 | Submit the payout | None |
| 11 | Read payment details and timeline | None |
| 12 | Transfer between FluxPay wallets | None |
| 13 | Create a policy document | `policyDocumentId` |
| 14 | Add or list policy chunks, then index the policy | None |
| 15 | Ask the Compliance Copilot a policy question | None |
| 16 | Create or list compliance cases | `complianceCaseId` |
| 17 | Approve, reject, or delete the compliance case as appropriate | None |

