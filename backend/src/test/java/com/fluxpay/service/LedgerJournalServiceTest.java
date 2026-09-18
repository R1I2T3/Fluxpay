package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.LedgerJournalLock;
import com.fluxpay.beans.LedgerTransactionCategory;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.LedgerJournalLockRepository;
import com.fluxpay.repository.LedgerJournalRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerJournalServiceTest {
  private final LedgerPostingContext context = new LedgerPostingContext();

  @Test
  void postsEveryBalancedLineWithItsJournalMetadata() {
    List<RecordedCall> calls = new ArrayList<>();
    LedgerWriter writer =
        (walletId, entryType, amount, currency, key) ->
            calls.add(
                new RecordedCall(walletId, entryType, amount, currency, key, context.current(key)));
    LedgerJournalService service = service(writer);
    UUID source = UUID.randomUUID();
    UUID target = UUID.randomUUID();

    service.post(
        "JRN-100",
        List.of(
            line(source, "DEBIT", "25.0000", "USD", "JRN-100:debit", "Customer debit"),
            line(target, "CREDIT", "25.0000", "USD", "JRN-100:credit", "Clearing credit")));

    assertEquals(2, calls.size());
    RecordedCall debit =
        calls.stream().filter(call -> call.key().endsWith(":debit")).findFirst().orElseThrow();
    RecordedCall credit =
        calls.stream().filter(call -> call.key().endsWith(":credit")).findFirst().orElseThrow();
    assertEquals(
        new RecordedCall(
            source,
            "DEBIT",
            new BigDecimal("25.0000"),
            "USD",
            "JRN-100:debit",
            new LedgerPostingContext.EntryMetadata("JRN-100", "Customer debit")),
        debit);
    assertEquals(
        new LedgerPostingContext.EntryMetadata("JRN-100", "Clearing credit"), credit.metadata());
    assertTrue(context.current("JRN-100:debit") == null);
  }

  @Test
  void rejectsImbalanceWithinEachCurrencyBeforePosting() {
    List<RecordedCall> calls = new ArrayList<>();
    LedgerJournalService service =
        service((walletId, entryType, amount, currency, key) -> calls.add(null));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.post(
                    "JRN-101",
                    List.of(
                        line(
                            UUID.randomUUID(), "DEBIT", "25.0000", "USD", "JRN-101:debit", "Debit"),
                        line(
                            UUID.randomUUID(),
                            "CREDIT",
                            "24.0000",
                            "USD",
                            "JRN-101:credit",
                            "Credit"))));

    assertTrue(error.getMessage().contains("USD"));
    assertTrue(calls.isEmpty());
  }

  @Test
  void rejectsDuplicateEntryKeysBeforePosting() {
    LedgerJournalService service =
        service((walletId, entryType, amount, currency, key) -> fail());
    String duplicate = "JRN-102:line";

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.post(
                    "JRN-102",
                    List.of(
                        line(UUID.randomUUID(), "DEBIT", "5.0000", "USD", duplicate, "Debit"),
                        line(UUID.randomUUID(), "CREDIT", "5.0000", "USD", duplicate, "Credit"))));

    assertTrue(error.getMessage().contains("Duplicate"));
  }

  @Test
  void clearsPostingContextWhenAWriterFails() {
    LedgerJournalService service =
        service(
            (walletId, entryType, amount, currency, key) -> {
              assertNotNull(context.current(key));
              throw new IllegalStateException("forced failure");
            });

    assertThrows(
        IllegalStateException.class,
        () ->
            service.post(
                "JRN-103",
                List.of(
                    line(UUID.randomUUID(), "DEBIT", "5.0000", "EUR", "JRN-103:debit", "Debit"),
                    line(
                        UUID.randomUUID(),
                        "CREDIT",
                        "5.0000",
                        "EUR",
                        "JRN-103:credit",
                        "Credit"))));

    assertNull(context.current("JRN-103:debit"));
  }

  @Test
  void postsWalletsInCanonicalUuidOrderToPreventLockOrderDeadlocks() {
    List<UUID> postingOrder = new ArrayList<>();
    LedgerJournalService service =
        service((walletId, entryType, amount, currency, key) -> postingOrder.add(walletId));
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID second = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    service.post(
        "JRN-ORDER",
        List.of(
            line(second, "CREDIT", "4.0000", "USD", "JRN-ORDER:credit", "Credit"),
            line(first, "DEBIT", "4.0000", "USD", "JRN-ORDER:debit", "Debit")));

    assertEquals(List.of(first, second), postingOrder);
  }

  @Test
  void rejectsAnExistingJournalReferenceWithDifferentPayload() {
    AccountingDatabase db = new AccountingDatabase();
    db.journals.post(
        "JRN-IDENTITY",
        List.of(
            line(db.customer, "DEBIT", "10.0000", "USD", "identity:first:debit", "Debit"),
            line(
                db.clearing,
                "CREDIT",
                "10.0000",
                "USD",
                "identity:first:credit",
                "Credit")));

    assertThrows(
        com.fluxpay.exception.LedgerIdempotencyConflictException.class,
        () ->
            db.journals.post(
                "JRN-IDENTITY",
                List.of(
                    line(
                        db.customer,
                        "DEBIT",
                        "11.0000",
                        "USD",
                        "identity:second:debit",
                        "Debit"),
                    line(
                        db.clearing,
                        "CREDIT",
                        "11.0000",
                        "USD",
                        "identity:second:credit",
                        "Credit"))));

    assertEquals(new BigDecimal("90.0000"), db.balance(db.customer));
    assertEquals(2, db.count());
  }

  @Test
  void exactJournalReplayDoesNotChangeBalancesTwice() {
    AccountingDatabase db = new AccountingDatabase();
    List<LedgerJournalLine> lines =
        List.of(
            line(db.customer, "DEBIT", "10.0000", "USD", "replay:debit", "Debit"),
            line(db.clearing, "CREDIT", "10.0000", "USD", "replay:credit", "Credit"));

    db.journals.post("JRN-REPLAY", lines);
    db.journals.post("JRN-REPLAY", lines);

    assertEquals(new BigDecimal("90.0000"), db.balance(db.customer));
    assertEquals(new BigDecimal("10.0000"), db.balance(db.clearing));
    assertEquals(2, db.count());
  }

  @Test
  void rejectsSubsetAndSupersetReplays() {
    AccountingDatabase db = new AccountingDatabase();
    List<LedgerJournalLine> original =
        List.of(
            line(db.customer, "DEBIT", "10.0000", "USD", "shape:debit", "Debit"),
            line(db.clearing, "CREDIT", "10.0000", "USD", "shape:clearing", "Clearing"),
            line(db.clearing, "DEBIT", "4.0000", "USD", "shape:fee-debit", "Fee debit"),
            line(db.fee, "CREDIT", "4.0000", "USD", "shape:fee-credit", "Fee credit"));
    db.journals.post("JRN-SHAPE", original);

    assertThrows(
        com.fluxpay.exception.LedgerIdempotencyConflictException.class,
        () -> db.journals.post("JRN-SHAPE", original.subList(0, 2)));
    assertThrows(
        com.fluxpay.exception.LedgerIdempotencyConflictException.class,
        () ->
            db.journals.post(
                "JRN-SHAPE",
                List.of(
                    original.get(0),
                    original.get(1),
                    original.get(2),
                    original.get(3),
                    line(
                        db.clearing,
                        "DEBIT",
                        "1.0000",
                        "USD",
                        "shape:extra:debit",
                        "Extra debit"),
                    line(
                        db.fee,
                        "CREDIT",
                        "1.0000",
                        "USD",
                        "shape:extra:credit",
                        "Extra credit"))));

    assertEquals(new BigDecimal("90.0000"), db.balance(db.customer));
    assertEquals(4, db.count());
  }

  @Test
  void permitsMultipleLinesInOneCurrency() {
    AccountingDatabase db = new AccountingDatabase();

    db.journals.post(
        "JRN-MULTI",
        List.of(
            line(db.customer, "DEBIT", "10.0000", "USD", "multi:debit", "Debit"),
            line(db.clearing, "CREDIT", "6.0000", "USD", "multi:clearing", "Clearing"),
            line(db.fee, "CREDIT", "4.0000", "USD", "multi:fee", "Fee")));

    assertEquals(3, db.count());
    assertEquals(new BigDecimal("6.0000"), db.balance(db.clearing));
    assertEquals(new BigDecimal("4.0000"), db.balance(db.fee));
  }

  @Test
  void rollsBackHeaderLinesAndBalancesWhenPersistenceFails() {
    AccountingDatabase db = new AccountingDatabase();
    db.failKey = "rollback:credit";

    assertThrows(
        IllegalStateException.class,
        () ->
            db.journals.post(
                "JRN-ROLLBACK",
                List.of(
                    line(
                        db.customer,
                        "DEBIT",
                        "10.0000",
                        "USD",
                        "rollback:debit",
                        "Debit"),
                    line(
                        db.clearing,
                        "CREDIT",
                        "10.0000",
                        "USD",
                        "rollback:credit",
                        "Credit"))));

    assertEquals(new BigDecimal("100.0000"), db.balance(db.customer));
    assertEquals(BigDecimal.ZERO.setScale(4), db.balance(db.clearing));
    assertEquals(0, db.count());
    assertEquals(0, db.journalCount());
  }

  @Test
  void rateAndQuoteRoundTripTogether() {
    AccountingDatabase db = new AccountingDatabase();
    String quoteId = "9c9bf4cc-cfdf-47ba-a778-35d333a188d0";

    db.journals.post(
        "JRN-FX-METADATA",
        LedgerTransactionCategory.SELF_TRANSFER,
        List.of(
            new LedgerJournalLine(
                db.customer,
                "DEBIT",
                new BigDecimal("10.0000"),
                "USD",
                "fx-meta:debit",
                "FX debit",
                new BigDecimal("83.50000000"),
                quoteId),
            new LedgerJournalLine(
                db.clearing,
                "CREDIT",
                new BigDecimal("10.0000"),
                "USD",
                "fx-meta:credit",
                "FX clearing",
                new BigDecimal("83.50000000"),
                quoteId)));

    assertEquals(new BigDecimal("83.50000000"), db.entry("fx-meta:debit").getRate());
    assertEquals(quoteId, db.entry("fx-meta:debit").getQuoteId());
  }

  @Test
  void rejectsSamePayloadWhenBusinessCategoryChanges() {
    AccountingDatabase db = new AccountingDatabase();
    List<LedgerJournalLine> lines =
        List.of(
            line(db.customer, "DEBIT", "10.0000", "USD", "category:debit", "Debit"),
            line(db.clearing, "CREDIT", "10.0000", "USD", "category:credit", "Credit"));
    db.journals.post("JRN-CATEGORY", LedgerTransactionCategory.SEND_MONEY, lines);

    assertThrows(
        com.fluxpay.exception.LedgerIdempotencyConflictException.class,
        () ->
            db.journals.post(
                "JRN-CATEGORY", LedgerTransactionCategory.WALLET_TO_WALLET, lines));

    assertEquals(2, db.count());
  }

  @Test
  void journalIdentityCannotBeForgedWithFieldDelimiterCharacters() {
    AccountingDatabase db = new AccountingDatabase();
    String delimiter = "\u001f";
    LedgerJournalLine originalDebit =
        line(
            db.customer,
            "DEBIT",
            "10.0000",
            "USD",
            "collision:a" + delimiter + "b",
            "c");
    LedgerJournalLine forgedDebit =
        line(
            db.customer,
            "DEBIT",
            "10.0000",
            "USD",
            "collision:a",
            "b" + delimiter + "c");
    LedgerJournalLine credit =
        line(db.clearing, "CREDIT", "10.0000", "USD", "collision:credit", "Credit");
    db.journals.post("JRN-COLLISION", List.of(originalDebit, credit));

    assertThrows(
        com.fluxpay.exception.LedgerIdempotencyConflictException.class,
        () -> db.journals.post("JRN-COLLISION", List.of(forgedDebit, credit)));

    assertEquals(2, db.count());
  }

  @Test
  void exactHistoricalNullHashReplayIsANoOp() {
    AccountingDatabase db = historicalJournalDatabase();
    List<LedgerJournalLine> lines =
        List.of(
            line(db.customer, "DEBIT", "10.0000", "USD", "historical:debit", "Debit"),
            line(db.clearing, "CREDIT", "10.0000", "USD", "historical:credit", "Credit"));

    db.journals.post("JRN-HISTORICAL", lines);

    assertEquals(new BigDecimal("90.0000"), db.balance(db.customer));
    assertEquals(new BigDecimal("10.0000"), db.balance(db.clearing));
    assertEquals(2, db.count());
  }

  @Test
  void changedHistoricalNullHashReplayConflicts() {
    AccountingDatabase db = historicalJournalDatabase();

    assertThrows(
        com.fluxpay.exception.LedgerIdempotencyConflictException.class,
        () ->
            db.journals.post(
                "JRN-HISTORICAL",
                List.of(
                    line(
                        db.customer,
                        "DEBIT",
                        "11.0000",
                        "USD",
                        "historical:changed:debit",
                        "Debit"),
                    line(
                        db.clearing,
                        "CREDIT",
                        "11.0000",
                        "USD",
                        "historical:changed:credit",
                        "Credit"))));

    assertEquals(new BigDecimal("90.0000"), db.balance(db.customer));
    assertEquals(2, db.count());
  }

  private static AccountingDatabase historicalJournalDatabase() {
    AccountingDatabase db = new AccountingDatabase();
    db.jdbc.update("update wallets set balance=90 where id=?", db.customer);
    db.jdbc.update("update wallets set balance=10 where id=?", db.clearing);
    db.jdbc.update(
        "insert into journal_headers values (?,?,?,?)",
        "JRN-HISTORICAL",
        "LEGACY",
        null,
        java.sql.Timestamp.from(java.time.Instant.EPOCH));
    db.jdbc.update(
        "insert into entries values (?,?,?,?,?,?,?,?,?)",
        "historical:debit",
        db.customer,
        "DEBIT",
        new BigDecimal("10.0000"),
        "USD",
        "JRN-HISTORICAL",
        "Debit",
        null,
        null);
    db.jdbc.update(
        "insert into entries values (?,?,?,?,?,?,?,?,?)",
        "historical:credit",
        db.clearing,
        "CREDIT",
        new BigDecimal("10.0000"),
        "USD",
        "JRN-HISTORICAL",
        "Credit",
        null,
        null);
    return db;
  }

  private static LedgerJournalLine line(
      UUID walletId, String type, String amount, String currency, String key, String narration) {
    return new LedgerJournalLine(walletId, type, new BigDecimal(amount), currency, key, narration);
  }

  private LedgerJournalService service(LedgerWriter writer) {
    LedgerJournalRepository journals = mock(LedgerJournalRepository.class);
    LedgerJournalLockRepository locks = mock(LedgerJournalLockRepository.class);
    when(locks.findByIdForUpdate(any())).thenReturn(java.util.Optional.of(mock(LedgerJournalLock.class)));
    when(journals.findByJournalReference(any())).thenReturn(java.util.Optional.empty());
    return new LedgerJournalService(
        writer, context, journals, locks, mock(LedgerEntryRepository.class), Clock.systemUTC());
  }

  private record RecordedCall(
      UUID walletId,
      String entryType,
      BigDecimal amount,
      String currency,
      String key,
      LedgerPostingContext.EntryMetadata metadata) {}
}
