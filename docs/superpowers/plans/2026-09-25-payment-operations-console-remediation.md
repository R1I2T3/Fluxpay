# Payment Operations Console Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the portable verification gaps found in the completed payment-operations-console review without changing its approved runtime design.

**Architecture:** Preserve the implemented backend, authorization, simulation, and active Oracle JET behavior. Repair brittle test contracts, isolate Python command tests from the developer's real `.env`, and remove the forbidden refund operation from the legacy frontend source tree so both active and legacy customer surfaces satisfy the same contract.

**Tech Stack:** Node.js `node:test`, TypeScript 5.3, Oracle JET CSS, Python 3 `unittest`/`unittest.mock`.

**Spec:** `docs/superpowers/specs/2026-09-24-payment-operations-console-design.md`

## Global Constraints

- Preserve the approved payment-operations-console runtime behavior and the existing `ADMIN` authorization flow.
- The customer frontend must not call `POST /api/payments/{id}/refund` from either active or legacy source trees.
- Do not edit generated `frontend/fluxpay-ui/web-dev` output.
- Treat `.45fr` and `0.45fr`-style CSS values as numerically equivalent; tests must not reject valid equivalent serialization.
- Python tests must not read the developer's real project `.env` unless that is the behavior under test.
- Text files are read explicitly as UTF-8 on Windows.
- Every production-code change is preceded by a failing test and every task runs its focused test plus the applicable full suite.

## Review Focus

- A CSS fractional value with or without a leading zero must satisfy the same layout contract.
- A real project `.env` containing complete integration credentials must not change missing-credential test outcomes.
- A real project `.env` selecting external infrastructure must not bypass the mocked Compose subprocess test.
- Windows' default non-UTF-8 locale must not make the README contract fail.
- The inactive `src/js` customer surface must expose no refund API, handler branch, or button.

---

## File Structure

- Modify `frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs` to accept equivalent fractional CSS notation.
- Modify `tests/test_demo_payment_operations.py` to use UTF-8 and prevent entry-point tests from loading the developer `.env`.
- Modify `tests/test_scripts.py` to pass explicit missing environment files for isolation-sensitive command tests.
- Modify `frontend/fluxpay-ui/tests/wallet-api.test.cjs` to cover the legacy customer's public operation surface.
- Modify `frontend/fluxpay-ui/src/js/services/api-client.ts` to remove the legacy refund request.
- Modify `frontend/fluxpay-ui/src/js/viewModels/tracking-vm.ts` to remove legacy refund dispatch.
- Modify `frontend/fluxpay-ui/src/js/views/tracking.html` to remove the legacy refund control.

### Task 1: Make the admin layout contract notation-independent

**Files:**
- Modify: `frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs`

**Interfaces:**
- Consumes: valid CSS fractional values in `frontend/fluxpay-ui/src/css/admin-console.css`.
- Produces: a contract test that accepts both `.NNfr` and `0.NNfr` notation while preserving all grid ratios and selectors.

- [ ] **Step 1: Verify the existing regression is red**

Run:

```powershell
node --test tests/admin-ui-contract.test.cjs
```

Expected: exactly the modal-workspace and compliance-review-queue assertions fail because the CSS uses leading-zero fractional values.

- [ ] **Step 2: Accept equivalent leading-zero notation**

Change only the affected numeric fragments in the two regular expressions:

```javascript
0?\.85fr
0?\.45fr
0?\.55fr
```

Keep the selector, column order, minimum widths, and overflow assertions unchanged.

- [ ] **Step 3: Run focused and full frontend verification**

Run:

```powershell
node --test tests/admin-ui-contract.test.cjs
npm.cmd test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```powershell
git add frontend/fluxpay-ui/tests/admin-ui-contract.test.cjs
git commit -m "test: accept equivalent admin grid fractions"
```

### Task 2: Isolate Python command tests from the workspace environment

**Files:**
- Modify: `tests/test_demo_payment_operations.py`
- Modify: `tests/test_scripts.py`

**Interfaces:**
- Consumes: script entry points that intentionally default `--env-file` to `.env`.
- Produces: deterministic tests whose environment file is explicit and whose README decoding is UTF-8.

- [ ] **Step 1: Verify the existing failures are red**

Run from the repository root:

```powershell
python -B -m unittest discover -s tests -v
```

Expected: the README decoding test fails under the Windows default codec and the isolated command tests are contaminated by the real project `.env`.

- [ ] **Step 2: Make README decoding explicit**

Use:

```python
readme = (ROOT / "README.md").read_text(encoding="utf-8")
```

- [ ] **Step 3: Keep demo entry-point validation isolated**

In entry-point tests that construct their complete environment with `mock.patch.dict(..., clear=True)`, add:

```python
mock.patch.object(self.script, "load_env")
```

This retains the production loader behavior while preventing the developer `.env` from filling deliberately missing test values.

- [ ] **Step 4: Pass an explicit absent env file to infrastructure command tests**

For `test_test_all_fails_when_requested_integration_credentials_are_incomplete` and `test_infrastructure_commands_run_from_project_root`, create a `TemporaryDirectory` and include:

```python
"--env-file",
str(Path(directory) / "missing.env"),
```

For the stop-infrastructure invocation also include `--mode`, `compose`, so the test selects the behavior it asserts instead of inheriting `FLUXPAY_INFRA_MODE`.

- [ ] **Step 5: Run focused and full Python verification**

Run:

```powershell
python -B -m unittest tests.test_demo_payment_operations tests.test_scripts -v
python -B -m unittest discover -s tests -v
```

Expected: all tests pass under the default Windows environment without `PYTHONUTF8=1`.

- [ ] **Step 6: Commit**

```powershell
git add tests/test_demo_payment_operations.py tests/test_scripts.py
git commit -m "test: isolate script suites from workspace env"
```

### Task 3: Remove the legacy customer refund operation

**Files:**
- Modify: `frontend/fluxpay-ui/tests/wallet-api.test.cjs`
- Modify: `frontend/fluxpay-ui/src/js/services/api-client.ts`
- Modify: `frontend/fluxpay-ui/src/js/viewModels/tracking-vm.ts`
- Modify: `frontend/fluxpay-ui/src/js/views/tracking.html`

**Interfaces:**
- Consumes: the approved customer authorization contract and the legacy `flux` operation surface.
- Produces: no customer refund API member, dispatch branch, or control in either frontend source tree.

- [ ] **Step 1: Add a failing legacy-surface contract**

Add a Node test that reads the three legacy customer files and asserts:

```javascript
assert.doesNotMatch(apiClientSource, /\/api\/payments\/\$\{id\}\/refund/);
assert.doesNotMatch(trackingViewModelSource, /action\s*===\s*['"]refund['"]/);
assert.doesNotMatch(trackingViewSource, /act\.bind\(\$data,['"]refund['"]\)/);
```

The production change that makes this test pass is removal of the forbidden legacy API request, handler branch, and button.

- [ ] **Step 2: Run the new contract and confirm RED**

Run:

```powershell
node --test tests/wallet-api.test.cjs
```

Expected: the new legacy-surface contract fails on all three forbidden remnants.

- [ ] **Step 3: Remove the legacy refund surface**

- Remove `refund` from the exported legacy `flux` object.
- Remove the `action === 'refund'` branch from `TrackingViewModel.act`.
- Remove the legacy Refund button from `src/js/views/tracking.html`.
- Preserve retry, route switching, cancellation, and every active `src/ts` implementation unchanged.

- [ ] **Step 4: Run frontend verification**

Run:

```powershell
node --test tests/wallet-api.test.cjs
npm.cmd test
npm.cmd run typecheck
npm.cmd run build
```

Expected: all tests pass, type checking succeeds, and the Oracle JET build completes.

- [ ] **Step 5: Commit**

```powershell
git add frontend/fluxpay-ui/tests/wallet-api.test.cjs frontend/fluxpay-ui/src/js/services/api-client.ts frontend/fluxpay-ui/src/js/viewModels/tracking-vm.ts frontend/fluxpay-ui/src/js/views/tracking.html
git commit -m "fix: remove legacy customer refund action"
```
