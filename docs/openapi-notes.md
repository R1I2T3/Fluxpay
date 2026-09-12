# OpenAPI notes

springdoc serves `/v3/api-docs` (JSON) and `/swagger-ui.html` (UI) via `springdoc-openapi-starter-webmvc-ui:2.5.0`. Stateless JWT; `permitAll: /api/auth/**,/swagger-ui/**,/v3/api-docs/**`.

The `mock`/`local` Spring profiles are retired: every endpoint is backed by concrete production modules (persistent wallets/ledger/FX, DB eligibility gates, M3 wallet/payment/Kafka adapters) in the default profile. There is no profile switch to reach stubbed behavior anymore — offline fallbacks (`fluxpay.fx-mode=mock|solo`, `fluxpay.embedding-mode=mock`) are the only documented toggles, and they are opt-in configuration, not a Spring profile.
