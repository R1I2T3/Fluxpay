# OpenAPI notes

Springdoc serves `/v3/api-docs` and `/swagger-ui.html`. Only authentication and API
documentation routes are public; all wallet, KYC, recipient, payment, payout, route,
and timeline endpoints require a valid JWT. The retired local-user header does not
authenticate requests in any Spring profile.

The default runtime has concrete Oracle repositories, the Frankfurter HTTP FX
adapter, a transactional outbox, scheduled Kafka relay, and timeline consumer. A
durable event ID means the event was committed to the outbox; it does not claim that
Kafka acknowledged delivery synchronously.

No real payout provider, compliance provider, or KYC file store is bundled. Their
absence produces explicit `503` responses. Development-only metadata KYC, demo
funding, always-approve compliance, and simulated payout implementations are disabled
by default and must be enabled individually. The metadata fixture never manufactures
a storage URL. Runtime mock/solo FX modes and logging-only event delivery are retired.

Payment lifecycle states are `DRAFT`, `QUOTED`, `UNDER_REVIEW`, `PROCESSING`,
`COMPLETED`, `FAILED`, `REFUNDED`, `REJECTED`, and `CANCELLED`. Mutating wallet and
payment operations require idempotency keys; payment action/key reuse conflicts are
reported instead of replaying another operation.

The canonical event envelope contains `eventType`, `eventId`, `paymentId`,
`correlationId`, `occurredAt`, and a payload with `schemaVersion=1` plus a positive
`aggregateSequence`. Event type equals the Kafka topic. Review and quarantine topics
are part of the documented inventory.

Delivery follows `route -> provider -> rail -> internal ledger/external network`. Rails are
code-shipped `TransferRail` implementations selected by `RailType` (`INTERNAL_LEDGER`,
`BANK_NETWORK`, `REAL_TIME_NETWORK`, `PARTNER_NETWORK`); administrators manage providers and
routes through `GET/POST/PUT/DELETE /api/admin/providers`, `/api/admin/routes`, and read-only
`GET /api/admin/rail-types`. Administrators never install integrations, endpoints, or
credentials, and quote generation persists at most the three top-ranked eligible routes.

See the root README for local reset/seed commands and the required Maven
`-Pintegration verify` environment.

Administrator statistics reads (`GET /api/admin/reports/statistics`,
`/statistics/options`, and `/statistics/payments`) are read-only `ADMIN` endpoints;
see the [API catalog](api-catalog.md#admin-statistics-reporting-api) and the
[statistics admin guide](admin-statistics.md).
