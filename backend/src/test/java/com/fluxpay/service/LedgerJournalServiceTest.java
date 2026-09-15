package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fluxpay.common.contracts.LedgerWriter;
import java.math.BigDecimal;
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
    LedgerJournalService service = new LedgerJournalService(writer, context);
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
        new LedgerJournalService(
            (walletId, entryType, amount, currency, key) -> calls.add(null), context);

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
        new LedgerJournalService((walletId, entryType, amount, currency, key) -> fail(), context);
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
        new LedgerJournalService(
            (walletId, entryType, amount, currency, key) -> {
              assertNotNull(context.current(key));
              throw new IllegalStateException("forced failure");
            },
            context);

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
        new LedgerJournalService(
            (walletId, entryType, amount, currency, key) -> postingOrder.add(walletId), context);
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID second = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    service.post(
        "JRN-ORDER",
        List.of(
            line(second, "CREDIT", "4.0000", "USD", "JRN-ORDER:credit", "Credit"),
            line(first, "DEBIT", "4.0000", "USD", "JRN-ORDER:debit", "Debit")));

    assertEquals(List.of(first, second), postingOrder);
  }

  private static LedgerJournalLine line(
      UUID walletId, String type, String amount, String currency, String key, String narration) {
    return new LedgerJournalLine(walletId, type, new BigDecimal(amount), currency, key, narration);
  }

  private record RecordedCall(
      UUID walletId,
      String entryType,
      BigDecimal amount,
      String currency,
      String key,
      LedgerPostingContext.EntryMetadata metadata) {}
}
