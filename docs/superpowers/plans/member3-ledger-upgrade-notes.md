# Member 3 ledger foundation: local upgrade notes

Work branch: `member3-money-ledger`. Do not merge into `main` until the ledger
changes have been reviewed. This note covers the Task 1 foundation upgrade and
the system-wallet provisioning required by Task 3 transfers.

## Compatibility decisions

- A journal header identifies the entire balanced operation. A reference is unique
  on that header, not on each debit/credit line. Same-currency lines share it.
- Accounting direction remains `DEBIT` or `CREDIT`. A separate category describes
  the business action; old journals are `LEGACY`.
- Existing four-decimal balances and amounts are not rounded or rewritten by the
  foundation migration. The currency table records the business minor-unit scale
  for the later math change.
- New rate and quote metadata are optional for historical/non-FX entries. An FX
  line must have both fields; one without the other is invalid.
- Existing schemas and Flyway history must not be dropped, repaired to hide checksum
  errors, or baselined over existing tables.

## Migration ordering

The integrated application has applied V001–V005 and V601–V605. The plan allocates
V007 and V008 to this track, so upgrading that database requires Flyway's explicit
out-of-order option. A fresh schema does not require the option.

Before an application upgrade, stop other backend processes and take a normal
database backup. Confirm the JDBC URL and username locally without sharing their
password. Prefer a dedicated test schema for the first run. Review **all** pending
migrations before enabling out-of-order mode, since the option is not restricted
to V007/V008.

For an approved local upgrade, in the same PowerShell terminal used for startup:

```powershell
$env:SPRING_FLYWAY_OUT_OF_ORDER = 'true'
python -B scripts/start-backend.py
```

Once migration completes and that backend is stopped, remove the temporary option
before the next normal startup:

```powershell
Remove-Item Env:SPRING_FLYWAY_OUT_OF_ORDER
```

Do not edit previously applied migration scripts. If migration fails, inspect the
specific error before retrying; Oracle DDL is not generally transactionally undone.

## Task 3 system-wallet provisioning

After V008 and the Task 3 V606 migration have been applied, the canonical system
user needs `FX_CLEARING`, `FX_GAIN_LOSS`, `DEMO_CLEARING`, `PAYOUT_CLEARING`, and
`FEE_REVENUE` wallets in each of USD, EUR, and INR (15 wallets in total).
`FX_GAIN_LOSS` receives or supplies rounding differences; for example, a TARGET
transfer of 100 INR at 83.50 credits 0.20 INR to this account. Without that wallet,
the transfer returns the existing system-account-unavailable error.

For local installations managed by `scripts/seed-local.py`, keep the same seed
identity credentials and rerun the seeder against the matching running backend:

```powershell
python -B scripts/seed-local.py --env-file .env
```

The wallet MERGE is insert-only and matches system user, currency, and role.
Rerunning adds the three missing FX_GAIN_LOSS wallets and preserves existing
wallet IDs, balances, holds, and ledger history. Confirm `system-wallets=15` on a
standard installation and that the reported system-user-id matches
`FLUXPAY_SYSTEM_USER_ID` used by the backend. The seeder also performs its existing
identity/role and demo-route setup; use the original local seed configuration.

The local seeder deliberately accepts only the local `FLUXPAY` schema. It does
not provision `FLUXPAY_INTEGRATED`; that installation's approved schema-specific
provisioning must ensure the same 15 canonical system wallets. Do not bypass the
seeder's schema guard or edit applied migrations to add these accounts. No seeder
or migration command was executed against an application database during Task 3.

## Local data isolation

`FLUXPAY` and `FLUXPAY_VSCODE` contain older project schemas. The integrated local
app uses `FLUXPAY_INTEGRATED`; tests use `FLUXPAY_TEST`. The latter was created
without dropping or resetting any existing schema. Task 1 development does not
automatically migrate the running application database.

As inspected on 2026-09-18 before Task 1, `FLUXPAY_INTEGRATED` had ten successful
migrations through V605, six wallets, and zero ledger entries. These are a
point-in-time observation, not assumptions for future migrations or tests.
