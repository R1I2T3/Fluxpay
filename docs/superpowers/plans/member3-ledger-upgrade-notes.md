# Member 3 ledger foundation: local upgrade notes

Work branch: `member3-money-ledger`. Do not merge into `main` until the ledger
changes have been reviewed. This note covers Task 1 only; fee/hold behavior and
the new transfer, withdrawal, and top-up APIs belong to Tasks 2 and 3.

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

## Local data isolation

`FLUXPAY` and `FLUXPAY_VSCODE` contain older project schemas. The integrated local
app uses `FLUXPAY_INTEGRATED`; tests use `FLUXPAY_TEST`. The latter was created
without dropping or resetting any existing schema. Task 1 development does not
automatically migrate the running application database.

As inspected on 2026-09-18 before Task 1, `FLUXPAY_INTEGRATED` had ten successful
migrations through V605, six wallets, and zero ledger entries. These are a
point-in-time observation, not assumptions for future migrations or tests.
