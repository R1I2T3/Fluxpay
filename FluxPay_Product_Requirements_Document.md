# FluxPay - Product Requirements and Build Specification

## 1. Purpose of this document

This document is the single build specification for FluxPay. An implementation agent should be able to create, run, test, and demonstrate the project using this document with minimal clarification.

FluxPay is an **intelligent multi-currency wallet prototype for individuals**. It lets a verified customer hold USD, INR, and EUR balances; simulate receiving money; convert currency; withdraw money to a saved bank recipient; compare payout routes; recover from a failed payout; and understand why a payment needs compliance review.

The system is an academic prototype. It must **never move real money**, call a real bank, store a real identity document, or make an automated compliance decision without an admin review path.

## 2. Product statement

> FluxPay is a multi-currency wallet that helps verified individuals hold, convert, and withdraw international funds. It recommends the best simulated payout route, recovers from payout failures, and explains payment-compliance decisions through a Payment Passport.

## 3. Scope and non-goals

### In scope

- Customer and admin authentication
- Simulated KYC submission and admin approval
- USD, EUR, and INR wallets
- Simulated incoming money
- Reference FX-rate lookup through a backend adapter
- Currency conversion with double-entry ledger records
- Recipient management
- Withdrawal/payment creation and route-wise quotes
- Smart routing: cheapest, fastest, balanced
- Kafka payment-event timeline
- Simulated payout success/failure and recovery
- Payment Passport: explainable risk reasons and actions
- Admin compliance review
- Oracle Database 23ai Vector Search for policy-document retrieval
- Oracle JET TypeScript frontend

### Explicitly out of scope

- Real payment-provider or bank integrations
- Real KYC/OCR/facial verification
- Real user documents, real account numbers, or real personal data
- Card payments, card tokenization, SWIFT integration, or live settlement
- Foreign-exchange trading or financial advice
- Multi-service deployment, Kubernetes, or mobile application

## 4. Roles and permissions

| Role | Permissions |
|---|---|
| `CUSTOMER` | Manage own profile/KYC, wallets, recipients, quotes, payments, and transaction history. |
| `ADMIN` | Review KYC, review compliance cases, manage payout-route configuration, upload/index policies, ask Compliance Copilot questions. |

Rules:

- A user may view only their own wallets, recipients, payments, ledger entries, and KYC record.
- Only `ADMIN` can approve/reject KYC or compliance cases.
- Only a `VERIFIED` customer may confirm a withdrawal.
- All authorization must be enforced in Spring Boot, not only hidden in Oracle JET.

## 5. Recommended technical stack

| Layer | Required technology |
|---|---|
| Frontend | Oracle JET, TypeScript, Oracle JET routing, REST client layer |
| Backend | Java 21, Spring Boot 3.x, Spring Web, Spring Validation, Spring Security, Spring Data JPA |
| Authentication | JWT access token; BCrypt password hashing |
| Database | Oracle Database 23ai, Oracle JDBC driver, Flyway migrations |
| Vector search | Oracle AI Vector Search with `VECTOR` column and `VECTOR_DISTANCE` |
| Messaging | Apache Kafka in KRaft mode; Spring Kafka |
| FX reference data | Backend-only `FxRateProvider` adapter, default implementation using Frankfurter API |
| Documentation | springdoc OpenAPI / Swagger UI |
| Testing | JUnit 5, Mockito, Spring Boot integration tests, Testcontainers where practical, frontend unit tests |
| Runtime | Docker Compose for app dependencies; local Node/Java development is acceptable |

Use an Oracle JET TypeScript starter created with:

```bash
ojet create fluxpay-ui --template=navdrawer --typescript
```

Oracle JET supports TypeScript starter templates and bundles type definitions with its npm package. Use its `oj-navigation-list`, `oj-form-layout`, `oj-table`, dialogs, tabs, and drawer patterns instead of custom controls wherever possible. [Oracle JET TypeScript overview](https://docs.oracle.com/en/middleware/developer-tools/jet/19/reference-api/TypescriptOverview.html)



Keep the backend as a **modular monolith**. Modules communicate through Java interfaces and Kafka events; do not create five independently deployed microservices.

## 7. UI and design system requirements

### 7.1 Layout

- Use a responsive Oracle JET nav-drawer application shell.
- Desktop: persistent left navigation and top header.
- Tablet/mobile: collapsible drawer.
- Header must show application name, signed-in user name, role, and logout action.
- Customer navigation: Dashboard, Exchange, Recipients, Send Money, Transactions, Profile & KYC.
- Admin navigation: KYC Reviews, Compliance Cases, Policies & Copilot, Payout Routes.
- Use route guards to redirect unauthenticated users to Login and unauthorized users to a 403 page.

### 7.2 Visual language

Use one consistent enterprise-fintech visual system:

| Token | Value | Use |
|---|---|---|
| Primary navy | `#102A56` | App header, primary heading, important navigation |
| Primary blue | `#1463D8` | Primary action, links, selected navigation |
| Success teal | `#0B8F85` | Completed, verified, safe states |
| Warning amber | `#B87510` | Pending review, recovery suggestions |
| Danger red | `#B04444` | Rejected, failed, high-risk states |
| Surface | `#FFFFFF` | Cards and forms |
| Background | `#F6F8FC` | Main page background |
| Border | `#D9E2F0` | Tables, cards, separators |

Rules:

- Use a clear page title, one-line supporting subtitle, and a primary action per screen.
- Use `oj-form-layout` for forms, `oj-table` for tabular content, `oj-dialog` for irreversible confirmation, `oj-status-meter-gauge` only when it adds meaning, and `oj-navigation-list` for navigation.
- Use status chips consistently: `VERIFIED/COMPLETED` teal, `PENDING/UNDER_REVIEW` amber, `FAILED/REJECTED` red, `DRAFT/QUOTED` blue/neutral.
- Every API mutation must show a busy state, success toast, and readable inline error message.
- Do not expose Java stack traces, SQL errors, JWTs, or raw Kafka payloads in the UI.
- Format money with a currency code: `USD 500.00`, `INR 41,349.00`, `EUR 80.00`.
- Store/transport money as decimals; never calculate money using JavaScript `number` without rounding/decimal handling.

### 7.3 Final screens: exactly 13

| # | Screen | Audience | Required UI behavior |
|---:|---|---|---|
| 1 | Login / Registration | Public | Tab or toggle between login and registration; display concise validation errors. |
| 2 | Wallet Dashboard | Customer | Currency wallet cards, quick actions, recent payments, KYC banner when not verified. |
| 3 | Profile and KYC | Customer | Profile form, KYC status timeline, document metadata form, submit/resubmit action. |
| 4 | Currency Conversion | Customer | Source/target wallet selectors, amount, market rate, fee, conversion preview, confirm dialog. |
| 5 | Recipient Management | Customer | Recipient table, add/edit dialog, active/blocked status, no real bank data in seeds. |
| 6 | Send Money | Customer | Step form: wallet, amount, destination, recipient, purpose, preference. |
| 7 | Quote Comparison | Customer | Three quote cards/table rows, recommended label, route choice, confirmation dialog. |
| 8 | Payment Detail | Customer | Status timeline, Payment Passport, retry/switch/refund controls when applicable. |
| 9 | Transaction History | Customer | Filterable payments and wallet ledger tabs; row click opens Payment Detail. |
| 10 | Admin KYC Review | Admin | KYC queue table, details drawer, approve/reject dialog with mandatory reason on reject. |
| 11 | Admin Compliance Cases | Admin | Case queue, risk reasons, payment details, approve/reject actions. |
| 12 | Policies and Copilot | Admin | Two tabs: Policy Documents and Compliance Copilot chat. |
| 13 | Payout Route Configuration | Admin | Editable simulated route configuration; activation toggle; route metrics. |

## 8. Database requirements

### 8.1 General database conventions

- Use `UUID` primary keys generated in Java or Oracle. Pick one strategy and use it everywhere.
- Use `NUMBER(19,4)` for money and exchange rates. Never use `FLOAT` for money.
- Use `VARCHAR2(3)` for ISO currency codes.
- Store all timestamps as `TIMESTAMP WITH TIME ZONE` in UTC.
- Every business table has `created_at`; operational tables also have `updated_at`.
- Use Flyway migration files named `V001__create_users.sql`, `V002__create_kyc.sql`, etc.
- Use foreign keys with `ON DELETE RESTRICT` semantics for financial/compliance data. Do not cascade-delete payments, ledgers, KYC, or policies.
- Add indexes for every foreign key and commonly filtered status/timestamp combination.
- Never update or delete a completed payment or ledger entry. Use a new compensating/refund record.

### 8.2 Tables and required fields

#### `users`

Purpose: customer and administrator identities.

```text
id                      UUID PK
role                    VARCHAR2(20) NOT NULL CHECK CUSTOMER|ADMIN
full_name               VARCHAR2(150) NOT NULL
email                   VARCHAR2(255) NOT NULL UNIQUE
password_hash           VARCHAR2(255) NOT NULL
mobile                  VARCHAR2(30)
date_of_birth           DATE
country                 VARCHAR2(2) NOT NULL
address                 VARCHAR2(500)
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
```

#### `kyc_applications`

Purpose: one current KYC application per user for this prototype.

```text
id                      UUID PK
user_id                 UUID NOT NULL UNIQUE FK users(id)
status                  VARCHAR2(20) NOT NULL CHECK NOT_STARTED|PENDING|VERIFIED|REJECTED
document_type           VARCHAR2(30)
document_number         VARCHAR2(100)
submitted_at            TIMESTAMP WITH TIME ZONE
reviewed_by             UUID FK users(id)
reviewed_at             TIMESTAMP WITH TIME ZONE
rejection_reason        VARCHAR2(1000)
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
```

#### `kyc_documents`

Purpose: simulated document metadata. Do not use real documents.

```text
id                      UUID PK
kyc_application_id      UUID NOT NULL FK kyc_applications(id)
document_type           VARCHAR2(30) NOT NULL CHECK PASSPORT|NATIONAL_ID|DRIVING_LICENCE|ADDRESS_PROOF
file_name               VARCHAR2(255) NOT NULL
file_path               VARCHAR2(500) NOT NULL
expiry_date             DATE
uploaded_at             TIMESTAMP WITH TIME ZONE NOT NULL
UNIQUE (kyc_application_id, document_type)
```

#### `wallets`

Purpose: each user has one wallet per currency.

```text
id                      UUID PK
user_id                 UUID NOT NULL FK users(id)
currency                VARCHAR2(3) NOT NULL CHECK USD|INR|EUR
available_balance       NUMBER(19,4) NOT NULL CHECK available_balance >= 0
held_balance            NUMBER(19,4) NOT NULL DEFAULT 0 CHECK held_balance >= 0
status                  VARCHAR2(20) NOT NULL CHECK ACTIVE|BLOCKED|CLOSED
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
UNIQUE (user_id, currency)
```

#### `recipients`

Purpose: saved bank beneficiaries.

```text
id                      UUID PK
user_id                 UUID NOT NULL FK users(id)
full_name               VARCHAR2(150) NOT NULL
country                 VARCHAR2(2) NOT NULL
currency                VARCHAR2(3) NOT NULL
bank_name               VARCHAR2(150) NOT NULL
account_number          VARCHAR2(150) NOT NULL
ifsc_or_swift           VARCHAR2(30)
status                  VARCHAR2(30) NOT NULL CHECK ACTIVE|BLOCKED|PENDING_VERIFICATION
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
UNIQUE (user_id, account_number, country)
```

Use fictional test numbers only. In a real product, encrypt/tokenize account data.

#### `payout_routes`

Purpose: admin-managed simulated payout route configuration.

```text
id                      UUID PK
route_code              VARCHAR2(50) NOT NULL UNIQUE
route_name              VARCHAR2(100) NOT NULL
provider_name           VARCHAR2(100) NOT NULL
route_type              VARCHAR2(30) NOT NULL CHECK STANDARD|INSTANT|LOCAL_PARTNER
base_fee                NUMBER(19,4) NOT NULL CHECK base_fee >= 0
fx_spread_percentage    NUMBER(9,6) NOT NULL CHECK fx_spread_percentage >= 0
estimated_minutes       NUMBER(10) NOT NULL CHECK estimated_minutes > 0
success_rate            NUMBER(5,2) NOT NULL CHECK success_rate BETWEEN 0 AND 100
active                  NUMBER(1) NOT NULL CHECK active IN (0,1)
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
```

Seed exactly three active routes: `STANDARD_BANK`, `INSTANT_PAYOUT`, `LOCAL_PARTNER`.

#### `payments`

Purpose: the central withdrawal/transfer record.

```text
id                      UUID PK
user_id                 UUID NOT NULL FK users(id)
source_wallet_id        UUID NOT NULL FK wallets(id)
recipient_id            UUID NOT NULL FK recipients(id)
selected_route_id       UUID FK payout_routes(id)
idempotency_key         VARCHAR2(100) NOT NULL UNIQUE
source_amount           NUMBER(19,4) NOT NULL CHECK source_amount > 0
source_currency         VARCHAR2(3) NOT NULL
payout_currency         VARCHAR2(3) NOT NULL
payment_purpose         VARCHAR2(500) NOT NULL
status                  VARCHAR2(30) NOT NULL
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
```

Allowed payment statuses:

```text
DRAFT -> QUOTED -> UNDER_REVIEW | PROCESSING
PROCESSING -> COMPLETED | FAILED | REFUNDED
QUOTED -> CANCELLED
UNDER_REVIEW -> PROCESSING | REJECTED
```

Validate that source wallet currency equals `source_currency`, recipient belongs to the authenticated customer, and source wallet has sufficient available balance at confirmation time.

#### `payment_quotes`

Purpose: immutable route quotes shown to the customer.

```text
id                      UUID PK
payment_id              UUID NOT NULL FK payments(id)
payout_route_id         UUID NOT NULL FK payout_routes(id)
market_rate             NUMBER(19,8) NOT NULL CHECK market_rate > 0
offered_rate            NUMBER(19,8) NOT NULL CHECK offered_rate > 0
fx_spread_percentage    NUMBER(9,6) NOT NULL CHECK fx_spread_percentage >= 0
fee_amount              NUMBER(19,4) NOT NULL CHECK fee_amount >= 0
recipient_amount        NUMBER(19,4) NOT NULL CHECK recipient_amount > 0
estimated_minutes       NUMBER(10) NOT NULL CHECK estimated_minutes > 0
quote_expires_at        TIMESTAMP WITH TIME ZONE NOT NULL
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
UNIQUE (payment_id, payout_route_id)
```

#### `payout_attempts`

Purpose: preserves every simulated provider attempt for recovery/audit.

```text
id                      UUID PK
payment_id              UUID NOT NULL FK payments(id)
payout_route_id         UUID NOT NULL FK payout_routes(id)
attempt_number          NUMBER(10) NOT NULL CHECK attempt_number > 0
status                  VARCHAR2(30) NOT NULL CHECK INITIATED|PROCESSING|COMPLETED|FAILED
failure_reason          VARCHAR2(1000)
provider_reference      VARCHAR2(100) UNIQUE
initiated_at            TIMESTAMP WITH TIME ZONE NOT NULL
completed_at            TIMESTAMP WITH TIME ZONE
UNIQUE (payment_id, attempt_number)
```

#### `ledger_entries`

Purpose: immutable double-entry accounting records.

```text
id                      UUID PK
payment_id              UUID FK payments(id)
wallet_id               UUID FK wallets(id)
journal_reference       VARCHAR2(100) NOT NULL
account_type            VARCHAR2(30) NOT NULL CHECK WALLET|PLATFORM_CLEARING|FEE_REVENUE
entry_type              VARCHAR2(10) NOT NULL CHECK DEBIT|CREDIT
currency                VARCHAR2(3) NOT NULL
amount                  NUMBER(19,4) NOT NULL CHECK amount > 0
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
```

The service must verify that each `journal_reference` balances: total debit amount equals total credit amount for each currency. Ledger entries must have no update/delete API.

#### `payment_events`

Purpose: durable audit timeline for Kafka/business events.

```text
id                      UUID PK
payment_id              UUID NOT NULL FK payments(id)
event_type              VARCHAR2(100) NOT NULL
event_payload           CLOB NOT NULL CHECK event_payload IS JSON
kafka_topic             VARCHAR2(150) NOT NULL
correlation_id          VARCHAR2(100) NOT NULL
occurred_at             TIMESTAMP WITH TIME ZONE NOT NULL
```

#### `compliance_cases`

Purpose: Payment Passport and manual compliance decision.

```text
id                      UUID PK
payment_id              UUID NOT NULL UNIQUE FK payments(id)
risk_level              VARCHAR2(10) NOT NULL CHECK LOW|MEDIUM|HIGH
status                  VARCHAR2(20) NOT NULL CHECK OPEN|APPROVED|REJECTED|CLOSED
risk_reasons            CLOB NOT NULL CHECK risk_reasons IS JSON
suggested_action        VARCHAR2(1000) NOT NULL
reviewed_by             UUID FK users(id)
reviewed_at             TIMESTAMP WITH TIME ZONE
decision_note           VARCHAR2(1000)
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
updated_at              TIMESTAMP WITH TIME ZONE NOT NULL
```

#### `policy_documents`

Purpose: original compliance policy source for the Copilot.

```text
id                      UUID PK
uploaded_by             UUID NOT NULL FK users(id)
title                   VARCHAR2(255) NOT NULL
category                VARCHAR2(30) NOT NULL CHECK KYC|AML|PAYMENT_REVIEW|COUNTRY_RULE|SUPPORT
content                 CLOB NOT NULL
document_hash           VARCHAR2(64) NOT NULL UNIQUE
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
```

#### `policy_chunks`

Purpose: searchable semantic chunks of policy documents.

```text
id                      UUID PK
policy_document_id      UUID NOT NULL FK policy_documents(id)
chunk_text              CLOB NOT NULL
embedding_vector        VECTOR(1536, FLOAT32) NOT NULL
chunk_number            NUMBER(10) NOT NULL CHECK chunk_number > 0
created_at              TIMESTAMP WITH TIME ZONE NOT NULL
UNIQUE (policy_document_id, chunk_number)
```

Create an Oracle vector index after the table is populated. Use the same embedding model, vector dimension, and distance metric for both document vectors and query vectors. Oracle Vector Search supports semantic retrieval with `VECTOR_DISTANCE`; the retrieval query must use the metric that matches the embedding model/index configuration. [Oracle AI Vector Search guide](https://docs.oracle.com/en/database/oracle/oracle-database/23/vecse/ai-vector-search-users-guide.pdf)

Example retrieval query:

```sql
SELECT pc.id,
       pd.title,
       pc.chunk_text,
       VECTOR_DISTANCE(pc.embedding_vector, :query_vector, COSINE) AS distance
FROM policy_chunks pc
JOIN policy_documents pd ON pd.id = pc.policy_document_id
ORDER BY VECTOR_DISTANCE(pc.embedding_vector, :query_vector, COSINE)
FETCH FIRST 5 ROWS ONLY;
```

## 9. Backend architecture and implementation requirements

### 9.1 Required modules

```text
auth        registration, login, JWT creation/validation
user        profile and current-user APIs
kyc         KYC application/document workflow and admin review
wallet      balances, simulated deposit, conversion
ledger      balanced journal-entry creation and read-only history
recipient   beneficiary management
payment     draft, quotes, confirmation, status transitions
routing     route recommendation and simulated payout adapters
event       Kafka producers/consumers and payment-event persistence
compliance  Payment Passport rules and admin decision
policy      policy upload, chunking, embedding, vector search, Copilot
common      error model, auditing, constants, API response helpers
```

### 9.2 Backend quality rules

- Use request/response DTOs; never expose JPA entities directly.
- Use `@Valid` and explicit validation annotations on all external request DTOs.
- Return one standard error shape:

```json
{
  "timestamp": "2026-09-03T10:00:00Z",
  "status": 400,
  "code": "KYC_NOT_VERIFIED",
  "message": "International withdrawals require verified KYC.",
  "fieldErrors": [{"field": "amount", "message": "must be greater than zero"}],
  "correlationId": "..."
}
```

- Add a correlation ID filter. Accept `X-Correlation-ID` or generate a UUID; put it in log MDC, API response headers, and `payment_events`.
- Use `@Transactional` for wallet conversion, payment confirmation, refund, and admin decision flows.
- Enforce idempotency on `POST /api/payments/{id}/confirm` using `Idempotency-Key`; a replay returns the original successful response.
- Use optimistic locking/version field on mutable high-contention entities such as `wallets`, `payments`, and `payout_routes`.
- Use `BigDecimal` in all Java money/rate calculations with explicit rounding mode and scale.

### 9.3 API contracts

All APIs use JSON, are prefixed with `/api`, and require JWT except registration/login/health endpoints.

#### Authentication and profile

```text
POST /api/auth/register
POST /api/auth/login
GET  /api/users/me
PUT  /api/users/me
```

`POST /api/auth/register` request:

```json
{
  "fullName": "Priya Sharma",
  "email": "priya@example.test",
  "password": "DemoPass123!",
  "country": "IN",
  "mobile": "+919999999999"
}
```

`POST /api/auth/login` response:

```json
{
  "accessToken": "<jwt>",
  "tokenType": "Bearer",
  "expiresInSeconds": 3600,
  "user": {"id": "...", "fullName": "Priya Sharma", "role": "CUSTOMER"}
}
```

#### KYC

```text
POST /api/kyc/applications
GET  /api/kyc/my-status
GET  /api/admin/kyc/applications?status=PENDING
PUT  /api/admin/kyc/{id}/approve
PUT  /api/admin/kyc/{id}/reject
```

KYC submit request:

```json
{
  "documentType": "PASSPORT",
  "documentNumber": "DEMO-PP-1234",
  "documents": [
    {"documentType": "PASSPORT", "fileName": "demo-passport.pdf", "filePath": "demo/kyc/demo-passport.pdf", "expiryDate": "2030-12-31"}
  ]
}
```

#### Wallet and FX

```text
GET  /api/wallets
POST /api/wallets/receive-demo
POST /api/wallets/convert
GET  /api/wallets/{walletId}/ledger
GET  /api/fx/rate?from=USD&to=INR
```

Conversion request:

```json
{
  "sourceWalletId": "...",
  "targetWalletId": "...",
  "amount": "100.00"
}
```

#### Recipients

```text
POST /api/recipients
GET  /api/recipients
PUT  /api/recipients/{recipientId}
```

#### Payments and quotes

```text
POST /api/payments/draft
POST /api/payments/{paymentId}/quotes
GET  /api/payments/{paymentId}/quotes
POST /api/payments/{paymentId}/confirm
GET  /api/payments
GET  /api/payments/{paymentId}
GET  /api/payments/{paymentId}/timeline
```

Payment draft request:

```json
{
  "sourceWalletId": "...",
  "recipientId": "...",
  "sourceAmount": "500.00",
  "payoutCurrency": "INR",
  "paymentPurpose": "Family support",
  "preference": "CHEAPEST"
}
```

Quote response must include all routes and a recommendation:

```json
{
  "paymentId": "...",
  "recommendedRouteId": "...",
  "recommendationReason": "Highest recipient amount for the selected lowest-cost preference.",
  "quotes": [
    {
      "routeId": "...",
      "routeName": "Standard Bank Rail",
      "marketRate": "83.50",
      "offeredRate": "83.08",
      "feeAmount": "3.50",
      "recipientAmount": "41349.00",
      "estimatedMinutes": 1440,
      "recommended": true
    }
  ]
}
```

#### Routing and recovery

```text
GET  /api/routes
POST /api/payments/{paymentId}/recommend-route
POST /api/payments/{paymentId}/submit-payout
POST /api/payments/{paymentId}/retry
POST /api/payments/{paymentId}/switch-route
POST /api/payments/{paymentId}/refund
PUT  /api/admin/routes/{routeId}
```

#### Compliance and policies

```text
POST /api/compliance/assess/{paymentId}
GET  /api/compliance/cases
GET  /api/compliance/cases/{caseId}
PUT  /api/compliance/cases/{caseId}/approve
PUT  /api/compliance/cases/{caseId}/reject
POST /api/policies
POST /api/policies/{policyId}/index
POST /api/copilot/ask
```

Copilot request:

```json
{
  "question": "Why was payment TXN-1042 flagged?",
  "paymentId": "optional-payment-id"
}
```

Copilot response:

```json
{
  "answer": "The payment needs review because it is the first international transfer to a new recipient.",
  "sources": [
    {"policyDocumentId": "...", "title": "New Recipient Review Policy", "chunkNumber": 2, "excerpt": "..."}
  ]
}
```

## 10. Feature behavior and end-to-end workflows

### 10.1 Registration, wallet creation, and KYC

1. Customer registers.
2. Backend creates `users` record with `CUSTOMER` role.
3. Backend creates three `wallets`: USD, EUR, INR; each balance is zero.
4. Dashboard shows a KYC warning banner and disables Send Money.
5. Customer completes Profile & KYC screen with simulated document metadata.
6. `kyc_applications.status` becomes `PENDING`.
7. Admin opens the KYC queue and approves/rejects.
8. On approval, status becomes `VERIFIED`; customer can create/confirm payments.

### 10.2 Simulated inbound money and conversion

1. Customer selects Receive Demo Money and enters a test amount/currency.
2. Backend credits available balance through a balanced journal in `ledger_entries`.
3. Dashboard refreshes wallet card and recent activity.
4. Customer opens Exchange, selects USD source wallet and INR target wallet, enters amount.
5. Backend calls `FxRateProvider`; it returns a market/reference rate.
6. Backend applies a documented demo conversion fee/spread and displays preview.
7. Confirmation creates ledger entries, debits source wallet, credits target wallet, and returns receipt.

### 10.3 Send-money workflow

1. Customer selects source wallet, recipient, amount, payout currency, purpose, and preference.
2. Create `payments` record with status `DRAFT` and unique idempotency key.
3. Generate quotes for every active `payout_route`.
4. Route recommendation uses user preference:
   - `CHEAPEST`: maximize recipient amount, then highest success rate.
   - `FASTEST`: minimize estimated minutes, then minimize fee.
   - `BALANCED`: weighted score using recipient amount (45%), time (30%), success rate (25%).
5. Customer chooses a quote and confirms through `oj-dialog`.
6. Backend verifies KYC, recipient ownership, quote validity, balance, and idempotency.
7. Compliance service evaluates Payment Passport.
8. Low-risk payment moves to `PROCESSING`; medium/high risk moves to `UNDER_REVIEW` and creates a `compliance_cases` record.
9. For `PROCESSING`, backend publishes `payment.initiated` and starts payout attempt.

### 10.4 Payment Passport rules

Use deterministic, explainable rules. Store reasons as JSON array; never use a black-box score.

| Rule | Risk contribution | Reason text |
|---|---:|---|
| KYC not verified | High | Customer identity is not verified. |
| First payment to recipient | Medium | This is the first payment to this recipient. |
| Recipient created today | Medium | Recipient bank details were recently added. |
| Amount exceeds demo threshold of USD 1,000 equivalent | Medium | Amount exceeds the configured review threshold. |
| Missing/short payment purpose | Medium | Payment purpose needs more detail. |
| Destination marked high-risk in demo config | High | Destination requires manual compliance review. |

Decision logic:

```text
Any high rule -> HIGH -> UNDER_REVIEW
Two or more medium rules -> MEDIUM -> UNDER_REVIEW
Otherwise -> LOW -> PROCESSING
```

The Payment Detail screen must show status, risk level, reasons, and suggested action. Suggested actions must be specific, e.g. "Confirm recipient bank details before payout."

### 10.5 Payout simulation, Kafka, and recovery

Use a provider abstraction:

```java
interface PayoutProvider {
    PayoutSubmissionResult submit(PayoutRequest request);
}
```

Implement three mock adapters. Their behavior must be deterministic under a test flag:

```text
StandardBankPayoutProvider
InstantPayoutProvider
LocalPartnerPayoutProvider
```

Required Kafka topics:

```text
payment.initiated
payment.route.selected
payment.screening.completed
payout.submitted
payout.failed
payout.completed
payment.refunded
```

Event contract minimum:

```json
{
  "eventId": "uuid",
  "eventType": "payout.failed",
  "paymentId": "uuid",
  "correlationId": "uuid",
  "occurredAt": "2026-09-03T10:00:00Z",
  "payload": {"attemptId": "uuid", "routeId": "uuid", "reason": "SIMULATED_PROVIDER_FAILURE"}
}
```

On failure:

1. Save failed `payout_attempts` record and `payment_events` event.
2. Do not debit money twice.
3. Identify next active route excluding prior failed route.
4. Show the customer a recovery action on Payment Detail: retry same route, switch route, or refund.
5. `switch-route` must generate a new payout attempt.
6. `refund` must restore wallet funds through a new balanced ledger journal and set payment status to `REFUNDED`.

### 10.6 Oracle Vector Search and Compliance Copilot

Purpose: the copilot answers policy questions, not financial or legal advice.

Seed at least five policy documents:

```text
KYC Verification Policy
New Recipient Review Policy
High Value Payment Policy
Payment Hold and Recovery Policy
Country Transfer Rules
```

Indexing flow:

1. Admin uploads policy title/category/content.
2. Backend calculates SHA-256 document hash and rejects duplicate content.
3. Split document into chunks of about 400-700 words with small overlap.
4. Generate one embedding per chunk through `EmbeddingProvider`.
5. Store chunk text and embedding in `policy_chunks`.
6. Query uses same embedding provider/model and searches top 5 closest chunks with cosine distance.
7. Build answer from retrieved chunks. Return title/chunk excerpts as sources.

Required abstraction:

```java
interface EmbeddingProvider {
    float[] embed(String text);
    int dimensions();
}
```

Configuration modes:

```text
EMBEDDING_MODE=api      # actual semantic demo, uses an OpenAI-compatible embedding endpoint
EMBEDDING_MODE=mock     # deterministic test-only vectors; never claim semantic accuracy
```

If API mode is absent, expose Copilot as unavailable with a configuration message instead of silently returning fabricated answers. The policy upload/list pages must still work.

## 11. FX provider requirements

Create an interface so external reference data can be replaced later:

```java
interface FxRateProvider {
    FxRateQuote getRate(String baseCurrency, String quoteCurrency);
}
```

Default implementation: call Frankfurter from Spring Boot only. Do not call it from the browser. Cache results for one hour, handle timeout/error, and preserve the exact rate in `payment_quotes`.

```text
GET https://api.frankfurter.dev/v2/rate/USD/INR
```

Frankfurter requires no API key and publishes current/historical exchange-rate data. Treat it as a reference rate, not a tradeable bank quote. [Frankfurter API documentation](https://frankfurter.dev/)

For every payout route:

```text
offered_rate = market_rate * (1 - fx_spread_percentage / 100)
net_source_amount = source_amount - base_fee
recipient_amount = net_source_amount * offered_rate
```

Round money to 2 decimals for display, preserve 4 decimals in the database, and never allow a fee greater than/equal to source amount.

## 12. Frontend implementation detail

### Shared frontend services

```text
auth-service.ts        login, logout, JWT storage, current user
api-client.ts          base URL, bearer token, correlation ID, error mapping
wallet-service.ts      wallets, deposit, conversion, ledger
kyc-service.ts         KYC APIs
recipient-service.ts   recipient APIs
payment-service.ts     draft, quotes, confirm, history, detail
route-service.ts       route admin APIs
compliance-service.ts  cases, decisions, copilot
```

JWT handling:

- Store token in memory/session storage for prototype simplicity.
- Attach `Authorization: Bearer <token>` to API calls.
- On HTTP 401, clear session and redirect to Login.
- On HTTP 403, show an access-denied message and hide restricted navigation.

### Screen-specific requirements

#### Wallet Dashboard

- Show USD, EUR, INR cards in fixed display order.
- Each card shows available and held balance.
- Quick actions: Receive Demo Money, Exchange, Send Money.
- If KYC is not verified, show amber banner with button to Profile & KYC; Send Money is disabled with explanation.
- Show latest five payments sorted by newest first.

#### Profile and KYC

- Profile fields: full name, country, mobile, date of birth, address.
- KYC section displays current status and review/rejection details.
- Document metadata only: type, number, expiry, simulated filename.
- Disable submit when required fields are missing.
- Rejected status must show rejection reason and allow resubmission.

#### Currency Conversion

- Prevent selecting the same source and target currency.
- Disable confirmation when source balance is insufficient.
- Show rate timestamp, fee, debited amount, credited amount.
- Use a confirmation dialog summarizing conversion before mutation.

#### Send Money and Quote Comparison

- Send Money must be a clear two-step route flow, not a long single page.
- Step 1: source wallet, recipient, amount, payout currency, purpose, preference.
- Step 2: quotes with route name, fee, offered rate, recipient amount, ETA, recommended label, and explanation.
- Sort quotes by selected preference but always visually highlight the recommendation.
- Quote expiry must be visible.
- Confirm button is enabled only after one selected quote.

#### Payment Detail

- Header: payment ID, amount, source/payout currency, current status chip.
- Tabs/sections: Summary, Timeline, Payment Passport, Recovery.
- Timeline displays persisted `payment_events` in chronological order.
- Payment Passport shows risk level, reasons, suggested action, and admin decision if present.
- Recovery section is visible only for `FAILED`; show allowed actions from backend response.

#### Admin screens

- Admin KYC and Compliance pages must use `oj-table` with status filter and row selection.
- Detail/review can open in an `oj-drawer-popup` or dialog.
- Reject action requires a non-empty reason.
- Policy screen has document table/upload form and Copilot tab.
- Copilot answer must render source titles/excerpts beneath the answer. Never present generated text without source references.

## 13. Seed data and scripts

Create safe, repeatable demo seed data. Never put real secrets or personal data in the repository.

### Required seeded users

```text
admin@fluxpay.test      role ADMIN     password DemoPass123!
priya@fluxpay.test      role CUSTOMER  password DemoPass123!  KYC VERIFIED
arjun@fluxpay.test      role CUSTOMER  password DemoPass123!  KYC PENDING
```

### Required seeded wallets for Priya

```text
USD 500.00
INR 10,000.00
EUR 50.00
```

### Required demo recipients

```text
Priya India Savings - INR - fictional IFSC/account
Alex Germany Bank - EUR - fictional IBAN/account
```

### Required scripts

| Script | Behavior |
|---|---|
| `scripts/start-infra.ps1` | Starts Kafka and any local supporting containers; checks readiness. |
| `scripts/stop-infra.ps1` | Stops only FluxPay containers. |
| `scripts/start-backend.ps1` | Runs Spring Boot with `local` profile. |
| `scripts/start-frontend.ps1` | Runs Oracle JET development server. |
| `scripts/seed-demo.ps1` | Runs idempotent demo seed endpoint/command. |
| `scripts/test-all.ps1` | Runs backend tests, frontend tests, and reports pass/fail. |

`.env.example` must document, without containing real values:

```text
ORACLE_JDBC_URL=
ORACLE_USERNAME=
ORACLE_PASSWORD=
KAFKA_BOOTSTRAP_SERVERS=
FX_PROVIDER_URL=https://api.frankfurter.dev
EMBEDDING_MODE=mock
EMBEDDING_API_URL=
EMBEDDING_API_KEY=
```

## 14. Testing requirements

### Backend unit tests

Test at least:

- Registration creates three wallets.
- Unverified customer cannot confirm a payment.
- Wallet conversion creates balanced debit/credit ledger entries.
- Negative balance is rejected.
- Quote calculations apply fee/spread correctly.
- Cheapest/fastest/balanced routing chooses expected route.
- Payment confirmation with same idempotency key does not create duplicate payment/ledger entries.
- Compliance rules produce expected risk level/reasons.
- Failed payout creates failed attempt/event and makes valid recovery options available.
- Refund restores wallet balance through ledger entries.
- Policy chunking persists expected chunks; vector query repository receives correct dimensions.

### Integration tests

- Run Flyway migrations against Oracle-compatible test environment where available.
- Exercise login -> KYC -> wallet deposit -> quote -> admin review -> payout -> timeline flow.
- Verify Kafka consumer persists a `payment_events` row.
- Verify ownership/role restrictions return 403.

### Frontend tests

- Route guards redirect unauthenticated user.
- KYC banner disables Send Money before verification.
- Send Money form validates amount and required recipient/purpose.
- Quote screen selects exactly one quote.
- Payment Detail conditionally shows recovery only after failure.
- Admin-only navigation is absent for customer role.

### Manual smoke-test script

1. Log in as Priya.
2. Confirm dashboard shows three wallets.
3. Convert USD to INR and inspect ledger history.
4. Create recipient if needed.
5. Draft USD -> INR withdrawal, obtain three quotes, choose Standard Bank route.
6. Confirm payment; inspect Payment Passport and timeline.
7. Log in as Admin; approve any review case.
8. Trigger simulated failure; switch to Instant Payout route.
9. Confirm final `COMPLETED` status and persisted timeline.
10. Ask Copilot why the payment required review; verify cited policy source appears.

## 15. Definition of done

The project is complete only when all of the following are true:

- All 14 business tables are created by Flyway migrations.
- All 13 screens are implemented and reachable by correct role.
- Customer registration creates USD/EUR/INR wallets.
- KYC can be submitted and approved/rejected by admin.
- FX conversion updates balances through balanced ledger entries.
- A customer can create a payment, compare three route quotes, and confirm one.
- Smart routing shows a human-readable recommendation.
- Payment Passport creates explainable reasons and admin can decide cases.
- Kafka events appear in the Payment Detail timeline.
- A deterministic simulated payout failure supports retry, route switch, or refund.
- Oracle Vector Search stores policy chunks and the Copilot returns sources with its answer.
- API is documented in Swagger UI.
- Seed script creates the exact demo story.
- Automated tests pass and the manual smoke-test script succeeds.
- README includes prerequisites, setup, environment variables, commands, test users, architecture, and demo instructions.

## 16. Team ownership mapping

| Member | Primary responsibility |
|---|---|
| Member 1 | Auth, users, KYC, Login/Registration/Profile/Admin KYC screens |
| Member 2 | Wallets, FX conversion, ledger, Dashboard/Exchange/Transactions screens |
| Member 3 | Recipients, payments, quotes, Send Money/Quote Comparison screens |
| Member 4 | Payout routes, attempts, Kafka events, Payment Detail/recovery/route admin screens |
| Member 5 | Compliance case, policy documents/chunks, Payment Passport, admin compliance, Vector Search, Copilot |

## 17. Agent implementation order

An implementation agent must follow this order to avoid integration rework:

1. Scaffold backend/frontend; create Docker/config scripts and `.env.example`.
2. Add Flyway migrations and verify full schema creation.
3. Implement authentication, users, roles, and seed users.
4. Implement wallets/ledger and seed wallet balances.
5. Implement KYC and admin approval workflow.
6. Implement recipient CRUD and payment draft/quote contracts.
7. Add FX provider adapter and quote calculation.
8. Implement frontend customer flow through quote confirmation.
9. Implement compliance rules and Admin case decision flow.
10. Add Kafka events, simulated payout providers, timeline, and recovery.
11. Implement policy upload/chunking/vector indexing/Copilot.
12. Add tests, API docs, UI polish, README, and execute smoke test.

Do not skip validation, migration, seed data, or tests to reach a UI demo faster. The core story must be correct: **KYC -> wallet -> quote -> review -> payout -> event timeline -> recovery -> ledger audit**.
