# FluxPay Bruno API Catalog

This catalog documents the original 46 HTTP API endpoints, followed by the September 20 integration additions below.

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
plus one inactive provider per external rail (`BANK_ALPHA`, `REAL_TIME`, `PARTNER`) with
representative `IN`/`INR`, `US`/`USD`, and `DE`/`EUR` routes.

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
| 31 | `POST {{baseUrl}}/api/payments/{{paymentId}}/refund` | Bearer + Idempotency-Key; owner only | No body | **200** `{"correlationId":"...","data":{"paymentId":"<payment-uuid>","eventId":"<event-uuid>","idempotentReplay":false}}` |
| 32 | `GET {{baseUrl}}/api/payments/{{paymentId}}/timeline` | Bearer token; owner only | None | **200** `{"correlationId":"...","data":[{"eventId":"<event-uuid>","paymentId":"<payment-uuid>","eventType":"payment.initiated","kafkaTopic":"payment.initiated","correlationId":"bruno-test-001","payload":{"schemaVersion":1,"aggregateSequence":1},"occurredAt":"2026-09-15T10:00:00Z"}]}` |

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
| Payout submission | Real payout provider is unavailable | `FLUXPAY_DEVELOPMENT_SIMULATED_PAYOUTS_ENABLED=true`; external demo providers stay inactive until an administrator activates them after the flag is enabled |
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

