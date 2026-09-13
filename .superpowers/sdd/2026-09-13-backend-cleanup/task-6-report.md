# Task 6 implementation report

Implementation baseline: `a75bc9372f06c0f68543ebdfddfb7350a32871cf`. Work was performed in the existing workspace/branch without a worktree, subagents, database reset, frontend edits, or provider/lifecycle redesign.

## Result and transaction architecture

- Added `PaymentOperationService` for payment-only request identities. Keys are unique per user across DRAFT, QUOTE, CONFIRM, CANCEL, SUBMIT, RETRY, SWITCH, and REFUND. Canonical request objects include action, requested payment identity, and the use-case request; confirmation includes quote ID and payout requests include route. Reordered object fields and equivalent JSON numbers normalize identically. Different payment/action/route/amount conflicts return 409.
- `execute` runs reservation insertion, database mutation, response serialization, and completion in one REQUIRES_NEW transaction. Draft, quote, confirmation, cancellation, and refund use it. Blocking confirmation is represented as a stored 422 result and thrown after the operation transaction commits, retaining its previous public error behavior.
- `reserve` commits an explicit IN_PROGRESS operation for payout submission/retry/switch. Its request is JSON; HTTP status and response are null. `complete` stores the full structured response and marks COMPLETED. Scalar response values are rejected.
- Both unique-key recovery paths catch DataIntegrityViolationException only after TransactionTemplate has rolled back. A new transaction reads the winner. Real H2/JPA tests capture the losing EntityManager, assert it is closed, assert the winner-read EntityManager is different, and cover both pending and completed winners. No failed-transaction JPA read remains.
- Added `WalletOperationService` for wallet-only replay/lookups/race recovery. WalletOperation and its operation-scoped key remain separate. WalletPostingService uses REQUIRES_NEW, so a race/optimistic conflict has unwound before the coordinator opens its fresh read transaction. Completed replay bypasses FX and posting; a posting retry retains its first FX snapshot. Existing validation, journal accounting, lock ordering, and two-attempt conflict behavior remain.
- Removed payment idempotency responsibilities and the release API from PaymentEligibilityGate/DbPaymentEligibilityGate. The gate now validates selected quotes only. Controllers no longer generate random keys. Payment and wallet mutating entry points enforce a nonblank key of at most 255 characters; payment missing/invalid keys use INVALID_IDEMPOTENCY_KEY with preserved correlation IDs.
- Payout replay returns the stored OutcomeResponse without querying changed attempt/quote state. Thus its data, including alreadyConfirmed/originalEventId, matches the originally stored response; correlationId remains request-specific.
- No uncertain payout reservation is deleted, released, or marked completed. Any exception after reservation leaves IN_PROGRESS and a duplicate gets OPERATION_IN_PROGRESS.

## Files

Production (all beneath `backend/src/main/java/com/fluxpay/`):

- New: `service/PaymentOperationService.java`, `service/WalletOperationService.java`, `service/OperationJson.java`.
- Persistence: `beans/PaymentOperation.java`, `beans/WalletOperation.java`, `repository/PaymentOperationRepository.java`.
- Use cases: `service/PaymentService.java`, `service/PaymentConfirmationService.java`, `service/QuoteService.java`, `service/DemoFundingService.java`, `service/WalletConversionService.java`, `service/WalletPostingService.java`.
- Boundaries: `controller/PaymentController.java`, `controller/PayoutController.java`, `common/contracts/PaymentEligibilityGate.java`, `service/DbPaymentEligibilityGate.java`.

New tests/support (beneath `backend/src/test/java/com/fluxpay/`):

- `service/OperationDatabase.java`: isolated real JPA/repository/transaction fixture.
- `service/PaymentOperationServiceTest.java`: exact replay, canonical JSON, amount/payment/action mismatches, pending state, 255/256 key boundary, invalid keys, scalar/failed serialization, atomic rollback, actual-EntityManager unique races.
- `service/WalletOperationServiceTest.java`: concurrent wallet uniqueness/replay and pending conflict.
- `service/PayoutOperationIntegrationTest.java`: real operation persistence with external execution stub; stored response survives changed attempt/quote state, route/action mismatches, uncertain delivery remains pending.
- `controller/PaymentKeyContractTest.java`: quote/cancel key validation and correlation IDs.

Updated tests: `config/ApplicationBoundaryWiringTest.java`; `controller/AdviceBoundaryTest.java`, `AuthorizationContractTest.java`, `PayoutControllerContractTest.java`; `service/DbPaymentEligibilityGateTest.java`, `DemoFundingServiceTest.java`, `WalletConversionServiceTest.java`, `PaymentAccountingTest.java`, `PaymentConfirmationQuoteTest.java`, `QuoteServiceTest.java`, `QuoteEntryPointsTest.java`, `FrozenPaymentCurrencyTest.java`, `WalletConversionPostingOracleTest.java`, `WalletPostingServiceOracleTest.java`. Old gate sentinel/mock-interaction tests were replaced by real persisted operation tests. Oracle slice imports were adapted but not executed.

## RED/GREEN evidence

Commands ran from the workspace root. Every Maven command below used this exact PowerShell prefix:

```powershell
$env:MAVEN_USER_HOME='C:\Users\Ritesh Jha\.m2'; $env:MAVEN_OPTS='-Duser.home="C:\Users\Ritesh Jha"'; .\mvnw.cmd -f backend/pom.xml
```

For each row, the remaining command arguments were `'-Dtest=<selector>' -l .superpowers/sdd/2026-09-13-backend-cleanup/<log>.log test`. Log names are shown without the `task6-` prefix and `.log` suffix. Cache/compiler access failures were rerun with scoped escalation. They are infrastructure failures, not behavioral RED evidence.

| Log | Test selector | Observed result |
|---|---|---|
| round1-red | DbPaymentEligibilityGateTest#firstConfirmCreatesPendingReservation | 1 test, 1 failure: expected null response, got empty string. |
| round1-green | Same selector | 1 passed after nullable completion/JSON pending request. |
| round2-red | PaymentOperationServiceTest | 1 failure: another payment silently replayed the key. |
| round2b-red | PaymentOperationServiceTest | 3 failures: wrong-payment replay, non-contract pending exception, scalar event snapshot. |
| round2-green | PaymentOperationServiceTest,DbPaymentEligibilityGateTest | 12 passed after payment coordinator extraction. |
| round3-red | PaymentOperationServiceTest | 10 tests: 5 failures and 2 errors. Action/invalid-key cases failed; canonical replay conflicted. One error was an invalid Mockito spy of a repository proxy. |
| round3b-red | PaymentOperationServiceTest | Proxy fixture corrected. Real concurrent loser escaped as DataIntegrityViolationException; canonical request conflicted; action/key regressions still failed. |
| round3-green | PaymentOperationServiceTest,DbPaymentEligibilityGateTest | 19 tests, 1 failure: canonical whole number rendered 1E+2 instead of the hand-derived 100 fixture. |
| round3b-green | Same selector | 19 passed after canonical integer formatting. |
| round4-red | PaymentOperationServiceTest | 12 tests, 2 missing-API compilation errors surfaced at runtime. This was test-first API absence, **not a valid behavioral RED**. |
| round4-green | PaymentOperationServiceTest | 12 passed after atomic execute API. |
| round4-refactor | PaymentOperationServiceTest,PaymentConfirmationQuoteTest,PaymentAccountingTest,AdviceBoundaryTest | 40 tests, 1 failure: old serializer fixture depended on the second ObjectNode allocation. Changed the external serializer failure trigger to the actual posting-snapshot payload. |
| round5-red | PaymentKeyContractTest,DemoFundingServiceTest,WalletConversionServiceTest,PaymentAccountingTest | 35 tests, 3 failures: missing cancel/quote keys returned 200/201; extraction fetched a second FX snapshot on retry. |
| round5-green | PaymentKeyContractTest,DemoFundingServiceTest,WalletConversionServiceTest,PaymentAccountingTest,QuoteServiceTest,QuoteEntryPointsTest,FrozenPaymentCurrencyTest | 47 passed after key enforcement and retaining one FX snapshot. |
| round6-red | PayoutControllerContractTest | 9 tests, 4 failures: all four payout mutations accepted missing keys. |
| round6-green | PaymentOperationServiceTest,PayoutControllerContractTest | 21 passed after distinct actions, stored payout snapshots, and required keys. |
| round7-red / round7-green | WalletOperationServiceTest | Compilation blocked by an obsolete gate constructor in a quote fixture. No behavioral evidence; reverted the tentative uniqueness mapping before correcting fixture and rerunning RED. |
| round7b-red | WalletOperationServiceTest | 2 tests, 1 failure: concurrent requests committed 2 wallet operation rows rather than 1. |
| round7b-green | WalletOperationServiceTest,PayoutOperationIntegrationTest | Wallet tests passed; 1 test error from re-stubbing an already throwing execution mock with when(). Changed to doThrow(). |
| final-core | PaymentOperationServiceTest,WalletOperationServiceTest,PayoutOperationIntegrationTest,PaymentAccountingTest,DemoFundingServiceTest,WalletConversionServiceTest | 51 passed, including corrected payout uncertainty fixture. |
| round4-atomic-boundary-red | PaymentOperationServiceTest#failedResponseSerializationRollsBackAtomicReservation | Deliberately restored the incorrect separate-commit boundary; 1 behavioral failure: expected no operation after serialization failure, found a persisted pending operation. This supplies the missing behavioral RED for the atomic boundary. |
| race-final-green | PaymentOperationServiceTest,WalletOperationServiceTest,PayoutOperationIntegrationTest | Restored atomic boundary: 18 passed, including pending/completed winner races with real EntityManager closure assertions. |
| round8-red | PaymentOperationServiceTest#scalarResponseCannotCompleteAnOperation | 1 failure: scalar response wrongly completed the reservation. |
| round8-green | PaymentOperationServiceTest,ApplicationBoundaryWiringTest | 16 passed after structured-response enforcement and wiring fixture adaptation. |

The tests use hand-derived JSON/response values. Race tests perform actual JPA inserts with actual uniqueness failures and transaction rollback. Accounting regressions retain real journal/balance assertions. Some external collaborators remain test doubles, and older unaffected tests still contain Mockito call-count assertions; those are not claimed as transaction proof.

## Schema obligations for Task 11

Do not launch the app against the old schema after these mapping changes.

1. Replace the historical `m3_payment_operations` table with `payment_operations`. Preserve UUID/RAW(16) identity/user/payment references and created_at. The operation_type mapping remains VARCHAR2-compatible (default JPA length 255); client_key is at most 255.
2. Enforce `uq_payment_operation_key(user_id, client_key)`, **without operation_type** in its scope.
3. Add required `status VARCHAR2(20)` with IN_PROGRESS/COMPLETED check. Make outcome_status and response_data nullable only for IN_PROGRESS; COMPLETED requires both. Add the combined state/completion consistency check.
4. normalized_request is a non-null CLOB with IS JSON validation. response_data is a nullable CLOB with IS JSON validation whenever present. Persisted completed snapshots are JSON objects, not plain UUID/event strings. Prove object fixtures, null pending completion, stored HTTP statuses, UUID round trips, duplicate rejection, and replay against Oracle.
5. payment_id must allow null during DRAFT reservation before the payment exists; atomic completion fills the created payment ID. Other operations provide the requested existing payment ID.
6. Retain wallet_operations separately with `uq_wallet_operation_key(user_id, operation_type, client_key)`, key length 255, normalized_request JSON, explicit status and response_snapshot JSON/completion consistency. The JPA uniqueness mapping now mirrors this requirement.
7. No migration SQL was edited and no database was reset in Task 6. Oracle-specific DDL/IS JSON behavior is unproven until Task 11.

## Self-review and deferred concerns

- Confirmed no keyless draft/confirm/cancel/quote compatibility overloads or controller random-key fallback remain. Internal payout/provider lifecycle methods retain their existing contracts for Task 7.
- Frozen route/quote economics and complete journals/refunds are preserved. A client requesting a new quote generation must use a new key; reuse intentionally replays its original stored quote response.
- Completion of an external reservation is a separate transaction from provider execution. Crashes/provider uncertainty remain pending for reconciliation. This task does not implement reconciliation, attempt provider idempotency, durable event dispatch, or lifecycle/status unification.
- Conservatively, even a known downstream validation failure after a payout reservation remains pending. Task 7 should move pre-delivery validation into its reservation/lifecycle boundary while retaining replay bypass. No uncertain delivery is treated as safe to release.
- The existing managed-wallet optimistic-lock availability limitation is not hidden or weakened: after the bounded retries an unresolved race remains a 409.
- Legacy migration/integration test schema names and current security expectation mismatches remain outside this task.
- A concurrent unrelated one-line edit in `docs/superpowers/plans/2026-09-13-backend-cleanup.md` was preserved and excluded from the Task 6 commit.

## Final verification and commits

Implementation commit: `262ff96` (`Consolidate domain operation replay and transaction race recovery`), 35 backend Java files. Report committed separately. No task-owned backend changes remain unstaged. The unrelated plan-document edit was excluded from the implementation commit; on resuming after the interruption, Git reported no remaining plan-document diff. This worker did not alter that document.

Final focused command (after the prefix above):

```powershell
'-Dtest=PaymentOperationServiceTest,WalletOperationServiceTest,PayoutOperationIntegrationTest,PaymentKeyContractTest,PayoutControllerContractTest,AuthorizationContractTest,AdviceBoundaryTest,ApplicationBoundaryWiringTest,DemoFundingServiceTest,WalletConversionServiceTest,PaymentAccountingTest,PaymentConfirmationQuoteTest,QuoteServiceTest,QuoteEntryPointsTest,FrozenPaymentCurrencyTest,DbPaymentEligibilityGateTest,RecoveryConcurrencyTest,PayoutExecutionServiceTest' -l .superpowers/sdd/2026-09-13-backend-cleanup/task6-final-focused.log test
```

Result: **108 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. The focused run includes the new coordinator dependency in the authorization MVC slice.

The single full-suite command was the same Maven prefix plus `-l .superpowers/sdd/2026-09-13-backend-cleanup/task6-full.log test`. Observed result: **376 tests, 4 failures, 6 errors, 53 skipped**. The four failures were the pre-existing expected-403/actual-401 checks in FxControllerTest, WalletControllerTest, and WalletReadControllerTest. One error was the pre-existing OracleAuthKycApiIntegrationTest application context failure. Five errors were AuthorizationContractTest slice startup failures from its missing PaymentOperationService mock; that fixture was corrected, and `'-Dtest=AuthorizationContractTest' -l .superpowers/sdd/2026-09-13-backend-cleanup/task6-auth-fixture-green.log test` passed all 5 tests. No second full run was performed, so no inferred corrected whole-suite count is claimed.

Formatting/check command (after the prefix above):

```powershell
'-DspotlessFiles=.*PaymentOperation.*\.java,.*WalletOperation.*\.java,.*OperationJson\.java,.*OperationDatabase\.java,.*PayoutOperationIntegrationTest\.java,.*PaymentKeyContractTest\.java,.*PaymentController\.java,.*PayoutController.*\.java,.*PaymentEligibilityGate.*\.java,.*DemoFundingService.*\.java,.*PaymentConfirmation.*\.java,.*PaymentService\.java,.*QuoteService.*\.java,.*WalletConversion.*\.java,.*WalletPosting.*\.java,.*AdviceBoundaryTest\.java,.*FrozenPaymentCurrencyTest\.java,.*PaymentAccountingTest\.java,.*QuoteEntryPointsTest\.java,.*ApplicationBoundaryWiringTest\.java' -l .superpowers/sdd/2026-09-13-backend-cleanup/task6-format.log spotless:apply spotless:check
```

Passed. The final authorization fixture was separately formatted/checked with `'-DspotlessFiles=.*AuthorizationContractTest\.java' -l .superpowers/sdd/2026-09-13-backend-cleanup/task6-auth-format.log spotless:apply spotless:check`, also passed. `git -c core.safecrlf=false diff --check` and the corresponding staged check passed. Expected unique-constraint errors appear in race-test logs and are asserted test events, not unhandled failures. Git's commit output noted existing unreachable loose objects; no pruning or repository cleanup was performed.
