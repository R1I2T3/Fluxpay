# Backend horizontal package architecture

## Goal

Replace the backend's feature-oriented package placeholders (`auth`, `wallet`,
`payment`, and similar) with a fixed set of horizontal layers. A feature is
represented by related classes across those layers rather than by a dedicated
top-level package.

## Target package layout

```
com.fluxpay

├── beans          # JPA and other application data objects
├── controller     # HTTP endpoints; one controller class per API area
├── dto            # Request and response transport records/classes
├── repository     # Spring Data persistence interfaces
├── service        # Use cases and business orchestration
├── config         # Application and framework configuration
└── common         # Shared API types, security, events, contracts, enums, and web support
```

`FluxPayApplication` remains in `com.fluxpay` so Spring Boot component scanning
covers every layer.

## Migration scope

The present `auth`, `compliance`, `event`, `kyc`, `ledger`, `payment`, `policy`,
`recipient`, `routing`, `user`, and `wallet` folders contain only empty
`package-info.java` declarations. The refactor will delete those placeholders
and create the fixed layer package markers. Existing `common` classes will stay
in place because they are cross-cutting rather than feature-owned.

No runtime behavior, database schema, routes, dependencies, or public API will
change. Future feature code will be placed by responsibility: for example,
`PaymentController`, `PaymentService`, `PaymentRepository`, payment beans, and
payment DTOs each live in their corresponding horizontal package.

## Verification

Run Maven tests and a clean compile after the package changes. Confirm the old
feature package directories are absent and the seven target layer directories
are present.
