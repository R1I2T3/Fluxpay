# Plan 3 — Money Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden ledger math plus wallet-to-wallet (same + cross-currency, all pairs), withdraw, and capped bank top-up.

**Architecture:** Own all wallet/ledger writes: fee schedule replaces hardcoded rate, FX re-quote with persisted rate columns, hold/commit via `heldBalance`, journal header with P&L dust account, central currency table; unified same + cross-currency P2P journal (single `wallet:p2p:<opId>`, sender-bears-fee, server quote, `rate/quote_id` per line) plus withdraw/topup journals reuse `LedgerJournalService`.

**Tech Stack:** Spring Boot / JPA / Flyway, JUnit, Oracle JET Add-money button wired by Plan 5.

**Spec:** `docs/superpowers/specs/2026-09-17-ledger-correctness-design.md` and `docs/superpowers/specs/2026-09-17-wallet-p2p-bankconnect-design.md`

## Approved implementation corrections (2026-09-18)

The two linked specifications are not present in this checkout. The following
corrections were approved before starting on branch `member3-money-ledger`:

- Keep `ledger_entries.entry_type` as `DEBIT` / `CREDIT`. Business labels such as
  `SELF_TRANSFER`, `WALLET_TO_WALLET`, `WALLET_TOPUP`, and `SEND_MONEY` belong in
  separate transaction-category metadata; existing records/callers use `LEGACY`.
- Put the unique journal reference on a `ledger_journals` header. Multiple debit
  and credit lines in the same currency **must** share a reference. Repeating an
  identical complete journal is a no-op; reusing it for different content fails.
- `journal_reference` and a unique `idempotency_key` already exist in V002. Do not
  add them again or add the sample `UNIQUE(journal_reference, currency)` below.
  Backfill historical journal headers without deleting or rewriting money amounts.
- Add V007/V008 without changing applied migrations. Existing installations at
  V605 require an explicit, controlled out-of-order migration; fresh installations
  apply the scripts in numeric order. Never reset an application schema to upgrade.
- Retain `NUMBER(19,4)` storage for compatibility. Introduce USD/EUR/INR minor-unit
  scale 2 in the currency table in Task 1; switch business rounding coherently in
  Task 2, rather than partially changing live posting behavior in Task 1.
- Commands below containing `-v` are sketches: Maven `-v` only prints its version.
  Omit `-v` when actually running tests, and use `mvnw.cmd` on Windows.

These corrections take precedence over contradictory example snippets below.

## Global Constraints

- Java 17, `spotless:apply`; amounts scale per currency table, never hardcoded 4.
- OWNERSHIP: this track alone edits `WalletController.java`, `WalletPostingService.java`, `WalletConversionService.java`, `LedgerJournalService.java`, `PersistentLedgerWriter.java`, `DemoFundingService.java`, `ConversionMath.java`, and creates `V007`, `V008`, `bank_accounts`, transfer/withdraw endpoints. Do NOT edit payout/compliance/copilot controllers or frontend layout/pages.
- Contract: exposes `POST /api/wallets/transfer` {toUserId|toEmail, fromCurrency, toCurrency, amount, amountMode: SOURCE|TARGET, note?}, `POST /api/wallets/withdraw`, `POST /api/bank-accounts/link`, `POST /api/bank-accounts/{id}/topup`, ledger `entry_type` values `SELF_TRANSFER/WALLET_TO_WALLET/WALLET_TOPUP/SEND_MONEY`.

---

### Task 1: V007+V008 schema + currency + journal integrity

**Files:**
- Create: `backend/src/main/resources/db/migration/V007__bank_accounts.sql`
- Create: `backend/src/main/resources/db/migration/V008__ledger_correctness.sql`
- Modify: `backend/src/main/java/com/fluxpay/beans/LedgerEntry.java:14-15`
- Test: `backend/src/test/java/com/fluxpay/service/LedgerJournalServiceTest.java`

**Interfaces:**
- Consumes: `wallets`, `users`. Produces: `bank_accounts`, `ledger_entries(rate, quote_id)`, `currencies(code,scale)`, unique `journal_reference`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void duplicateJournalReferenceRejected() {
  post(firstJournal("J-1"));
  assertThrows(DataIntegrityViolationException.class, () -> post(secondJournal("J-1")));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=LedgerJournalServiceTest#duplicateJournalReferenceRejected -v`
Expected: FAIL with duplicate allowed

- [ ] **Step 3: Write minimal SQL**

```sql
ALTER TABLE ledger_entries ADD (rate NUMBER(19,8) NULL, quote_id VARCHAR2(36) NULL, journal_reference VARCHAR2(64) NOT NULL);
ALTER TABLE ledger_entries ADD CONSTRAINT uq_ledger_idem UNIQUE (idempotency_key);
ALTER TABLE ledger_entries ADD CONSTRAINT uq_ledger_jref UNIQUE (journal_reference, currency);
CREATE TABLE bank_accounts (id RAW(16) PRIMARY KEY, user_id RAW(16) NOT NULL REFERENCES users(id), bank_name VARCHAR2(80) NOT NULL, account_last4 CHAR(4) NOT NULL, currency CHAR(3) NOT NULL, status VARCHAR2(16) NOT NULL);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=LedgerJournalServiceTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V007__bank_accounts.sql backend/src/main/resources/db/migration/V008__ledger_correctness.sql
git commit -m "feat(ledger): add bank accounts and journal integrity"
```

### Task 2: Fees + FX re-quote + holds

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/domain/ConversionMath.java:8`
- Modify: `backend/src/main/java/com/fluxpay/service/WalletConversionService.java:37-50`
- Modify: `backend/src/main/java/com/fluxpay/service/PersistentLedgerWriter.java:84-91`
- Test: `backend/src/test/java/com/fluxpay/domain/ConversionMathTest.java`

**Interfaces:**
- Consumes: `FxSnapshot`. Produces: single `fee/net/credit` computation, stale rejection, hold/commit.

- [ ] **Step 1: Write the failing test**

```java
@Test
void staleQuoteRejected() {
  assertThrows(QuoteExpiredException.class,
    () -> conversion.convert(user, req(staleSnapshot()), key()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=ConversionMathTest -v`
Expected: FAIL with stale accepted

- [ ] **Step 3: Write minimal implementation**

```java
public Quote quote(BigDecimal amount, FxSnapshot snap) {
  if (snap.stale()) throw new QuoteExpiredException("REQUOTE_REQUIRED");
  BigDecimal fee = feeSchedule.rate(snap.currency()).multiply(amount).setScale(scale, HALF_UP);
  BigDecimal net = amount.subtract(fee);
  return new Quote(fee, net, net.multiply(snap.rate()));
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=ConversionMathTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/domain/ConversionMath.java backend/src/main/java/com/fluxpay/service/WalletConversionService.java
git commit -m "fix(ledger): fee schedule and fx re-quote"
```

### Task 3: Transfer (same + cross-currency) + withdraw + capped topup

**Files:**
- Modify: `backend/src/main/java/com/fluxpay/controller/WalletController.java`
- Modify: `backend/src/main/java/com/fluxpay/service/WalletPostingService.java:48-85`
- Modify: `backend/src/main/java/com/fluxpay/service/DemoFundingService.java:30-48`
- Test: `backend/src/test/java/com/fluxpay/service/WalletTransferTest.java`

**Interfaces:**
- Consumes: Task 1 tables + Task 2 quote (`FxSnapshot`, `fee/net/credit`). Produces: transfer/withdraw/topup endpoints with `entry_type` tags. Transfer accepts `fromCurrency/toCurrency/amountMode`; `from==to` → same-currency debit/credit, else cross-currency FX journal via `FX_CLEARING/FEE_REVENUE/FX_GAIN_LOSS`.

- [ ] **Step 1: Write the failing test**

```java
@Test
void p2pConservesBalance() {
  transfer(sender, recipient, "USD", "USD", "SOURCE", new BigDecimal("100.00"));
  assertEquals(new BigDecimal("900.00"), balance(sender, "USD"));
  assertEquals(new BigDecimal("100.00"), balance(recipient, "USD"));
}

@Test
void p2pCrossCurrencySourceMode() {
  // sender INR -1000 gross == fee + net; recipient USD +net*rate; rate/quote_id persisted
  transfer(sender, recipient, "INR", "USD", "SOURCE", new BigDecimal("1000.00"));
  assertRecipientCredited("USD", net("INR", "1000.00").multiply(rate("INR", "USD")));
}

@Test
void p2pCrossCurrencyTargetMode() {
  // recipient gets exact USD target; sender debited grossed-up INR + fee
  transfer(sender, recipient, "INR", "USD", "TARGET", new BigDecimal("100.00"));
  assertEquals(new BigDecimal("100.00"), balance(recipient, "USD"));
}

@Test
void p2pStaleQuoteRejected() {
  assertThrows(QuoteExpiredException.class,
    () -> transferWithSnapshot(sender, recipient, staleSnapshot()));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw -f backend/pom.xml test -Dtest=WalletTransferTest#p2pConservesBalance -v`
Expected: FAIL with "transfer not defined"

- [ ] **Step 3: Write minimal implementation**

```java
@PostMapping("/transfer")
public ApiResponse<WalletResponse> transfer(@AuthenticationPrincipal CurrentUser u,
  @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody TransferRequest r) {
  return new ApiResponse<>(cid(), posting.transfer(u.userId(), r, key));
}
```

```java
// WalletPostingService.transfer: from==to → debit sender / credit recipient (fee 0 v1).
// Else cross-currency (e.g. INR→USD): server FxSnapshot(from,to); stale → REQUOTE_REQUIRED;
// SOURCE: credit=(amount-fee)*rate; TARGET: gross-up + recompute-validate; sender bears fee.
// Single journal wallet:p2p:<opId> entry_type=WALLET_TO_WALLET:
// debit sender-source (gross) / credit FX_CLEARING (net, source ccy) + FEE_REVENUE (fee)
// / debit FX_CLEARING (credit, target ccy) / credit recipient-target; rate+quote_id per line,
// dust → FX_GAIN_LOSS; recipient (toCurrency) auto-provisioned, sender funds-checked.
```

```java
// DemoFundingService: enforce daily cap 10000 + require VERIFIED
if (!kycGate.isVerified(userId)) throw new ForbiddenException("KYC_NOT_VERIFIED");
if (dailyTotal(userId, currency).add(amount).compareTo(DAILY_CAP) > 0) throw new BusinessException(CONFLICT, "TOPUP_CAP_EXCEEDED", "...");
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw -f backend/pom.xml test -Dtest=WalletTransferTest -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/fluxpay/controller/WalletController.java backend/src/main/java/com/fluxpay/service/WalletPostingService.java
git commit -m "feat(wallet): add p2p transfer and capped topup"
```
