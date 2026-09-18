# Simulation Rules and Chaos Design

**Date:** 2026-09-18  
**Status:** Design approved; written specification awaiting review  
**Scope:** Complete Task 3 of `2026-09-17-plan2-trust-backend.md` without changing production provider behavior.

## Context

The trust-backend plan requires deterministic QA triggers for compliance screening and payout settlement. Two settlement triggers already exist: the local-partner simulator rejects amounts above 50,000 with `LIMIT_EXCEEDED`, and the standard-bank simulator can return `UNCERTAIN` when `SIMULATE_FAILURE` targets that provider. The remaining sanctions trigger cannot currently be implemented faithfully because `ComplianceAssessor` receives only the sender ID, amount, and currency; it cannot see the selected recipient name.

## Requirements

- A recipient name containing `SANCTIONED_ACME`, compared case-insensitively, produces a compliance assessment with verdict `BLOCK`, risk `HIGH`, reason `SANCTIONS_HIT`, and a clear stop-payment action.
- All other inputs retain the simulator's existing approval behavior.
- The real amount-based and unavailable compliance assessors remain behaviorally unchanged.
- The simulation remains synchronous and is active only when the existing development simulation flags are explicitly enabled.
- The local-partner simulator continues to return `LIMIT_EXCEEDED` above 50,000.
- The standard-bank simulator continues to support deterministic uncertainty through `SIMULATE_FAILURE=STANDARD_BANK` and counted failures through `STANDARD_BANK:N`.
- A synthetic active recipient named `SANCTIONED_ACME` is available for manual QA.
- No simulation flag is enabled by default or in production-like configuration.

## Architecture

Introduce an immutable `ComplianceScreeningInput` in the compliance contract package. It carries the sender ID, recipient name, amount, and currency. `ComplianceAssessor` gains input-based default methods that delegate to the existing three-argument methods. This keeps existing assessor implementations and callers source-compatible while allowing recipient-aware implementations to override the richer path.

`PaymentConfirmationService` already locks and validates the exact recipient used by a payment. It will build the screening input from that recipient and pass the same input to both verdict and detailed-assessment calls. The service will not perform sanctions matching itself; policy remains behind the `ComplianceAssessor` boundary.

`SimulatedComplianceAssessor` will override the input-based methods. It normalizes the recipient name with `Locale.ROOT`; a `SANCTIONED_ACME` fragment match returns the sanctions assessment, while blank, absent, or ordinary names approve. The legacy three-argument method remains an approval path for callers that have no recipient context.

## Data Flow

1. Payment confirmation loads and locks the owned payment and its selected recipient.
2. It validates the quote, route, recipient version, eligibility, and KYC as it does today.
3. It creates one `ComplianceScreeningInput` from the sender, recipient name, source amount, and source currency.
4. The configured assessor evaluates that input within the existing three-second timeout.
5. `BLOCK` rejects the payment before any ledger posting or payout submission. `REVIEW` and `APPROVE` retain their existing flows.
6. With simulated compliance disabled, the production/default assessors receive the input through compatibility delegation and behave exactly as before.

## Simulation Rules

| Simulator | Trigger | Result |
|---|---|---|
| Compliance | Recipient name contains `SANCTIONED_ACME` | `BLOCK`, `HIGH`, `SANCTIONS_HIT` |
| Local partner | Amount greater than 50,000 | Failed result with `LIMIT_EXCEEDED` |
| Standard bank | `SIMULATE_FAILURE=STANDARD_BANK` | Uncertain result with `PROVIDER_TIMEOUT` |
| Standard bank | `SIMULATE_FAILURE=STANDARD_BANK:N` | First `N` executions per payment are uncertain, then success |

## Seed Migration

Create `V607__simulation_seed.sql`, following the current migration head `V606`. The older plan's proposed `V009` version would be out of order and is not valid for this repository.

The migration adds one clearly synthetic, active, profile-complete recipient named `SANCTIONED_ACME` under an existing `.test` fixture user. It does not enable simulation properties. The row is inert unless the development-only simulated compliance bean is selected.

## Error Handling and Safety

- The screening input validates required sender, amount, and currency values; recipient name may be blank for compatibility callers.
- Name normalization is deterministic and locale-independent.
- Simulation checks remain confined to classes guarded by the existing `fluxpay.development.*-enabled` properties.
- Provider uncertainty remains an outcome, not an exception that could accidentally start a fresh payout. Existing reconciliation behavior remains unchanged.
- No production endpoint, topic, or persistence state machine changes as part of this task.

## Testing

Use test-driven development:

1. Add failing simulation tests for the sanctions verdict, high risk, reason, case-insensitive matching, and ordinary approval.
2. Add a payment-confirmation test proving the locked recipient name reaches compliance screening and a sanctions hit prevents posting.
3. Retain and run provider tests for the local limit and standard-bank uncertainty/count behavior.
4. Add a migration contract test proving the synthetic recipient exists with active, complete fields and that migration ordering remains valid.
5. Run Spotless, focused tests, the complete backend test suite, and the repository's acceptance test where its Oracle/Kafka prerequisites are available.

## Non-goals

- Building a general sanctions-list database or fuzzy-matching engine.
- Enabling simulation in production-like environments.
- Adding asynchronous simulation behavior.
- Changing refund, retry, reconciliation, wallet, ledger, frontend, or copilot behavior.
