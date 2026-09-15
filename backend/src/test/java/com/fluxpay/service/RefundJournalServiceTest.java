package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

/** The real journal and persistent writer must preserve every account under replay and failure. */
class RefundJournalServiceTest {
  final PaymentAccountingTest fixture = new PaymentAccountingTest();
  final AccountingDatabase db = fixture.db;
  final RefundJournalService refunds =
      db.transactional(new RefundJournalService(db.journals, db.writer));

  @Test
  void concurrentFullFeeRefundsReverseExactlyThreeEntriesOnce() throws Exception {
    fixture.confirm("5.0000");
    var originals =
        db.jdbc.queryForList(
            "select * from entries where journal=? order by entry_key",
            "payment:" + fixture.paymentId);
    var executor = Executors.newFixedThreadPool(4);
    var start = new CountDownLatch(1);
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int i = 0; i < 4; i++)
        results.add(
            executor.submit(
                () -> {
                  try {
                    start.await();
                  } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                  }
                  refunds.refund(fixture.snapshot("5.0000"));
                }));
      start.countDown();
      for (Future<?> result : results) result.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("0.0000");
    assertThat(db.count()).isEqualTo(6);
    assertThat(
            db.jdbc.queryForList(
                "select * from entries where journal=? order by entry_key",
                "payment:" + fixture.paymentId))
        .isEqualTo(originals);
    assertThat(refunds.isAlreadyRefunded(fixture.snapshot("5.0000"))).isTrue();
  }

  @Test
  void failureAfterClearingDebitRollsBackAndRetryRestoresAllAccounts() {
    fixture.confirm("5.0000");
    db.failKey = "refund:" + fixture.paymentId + ":fee:debit";
    assertThatThrownBy(() -> refunds.refund(fixture.snapshot("5.0000")))
        .hasMessageContaining("controlled persistence failure");
    assertThat(db.balance(db.customer)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("95.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("5.0000");
    assertThat(db.count()).isEqualTo(3);
    assertThat(refunds.isAlreadyRefunded(fixture.snapshot("5.0000"))).isFalse();
    db.failKey = null;
    refunds.refund(fixture.snapshot("5.0000"));
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("0.0000");
    assertThat(db.count()).isEqualTo(6);
  }

  @Test
  void zeroFeeRefundHasTwoLegsAndReplayDoesNotNeedAFeeEntry() {
    fixture.confirm("0.0000");
    refunds.refund(fixture.snapshot("0.0000"));
    refunds.refund(fixture.snapshot("0.0000"));
    assertThat(db.count()).isEqualTo(4);
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("0.0000");
    assertThat(refunds.isAlreadyRefunded(fixture.snapshot("0.0000"))).isTrue();
  }

  @Test
  void journalCannotCombineOneOldRefundEntryWithNewEntries() {
    fixture.confirm("5.0000");
    db.jdbc.update(
        "insert into entries values (?,?,?,?,?,?,?)",
        "refund:" + fixture.paymentId + ":clearing:debit",
        db.clearing,
        "DEBIT",
        new java.math.BigDecimal("95.0000"),
        "USD",
        "refund:" + fixture.paymentId,
        "Reversal of payment:" + fixture.paymentId);
    assertThatThrownBy(() -> refunds.refund(fixture.snapshot("5.0000")))
        .hasMessageContaining("cannot mix replayed and newly posted entries");
    assertThat(db.balance(db.customer)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("95.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("5.0000");
    assertThat(db.count()).isEqualTo(4);
    assertThat(refunds.isAlreadyRefunded(fixture.snapshot("5.0000"))).isFalse();
  }

  @Test
  void failedConfirmationRollsBackEarlierCreditsAndAnExactReplayCannotDebitAgain() {
    db.failKey = "payment:" + fixture.paymentId + ":customer";
    assertThatThrownBy(() -> fixture.confirm("5.0000"))
        .hasMessageContaining("controlled persistence failure");
    assertThat(db.balance(db.customer)).isEqualByComparingTo("100.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("0.0000");
    assertThat(db.count()).isZero();
    db.failKey = null;
    fixture.confirm("5.0000");
    fixture.confirm("5.0000");
    assertThat(db.count()).isEqualTo(3);
    assertThat(db.balance(db.customer)).isEqualByComparingTo("0.0000");
    assertThat(db.balance(db.clearing)).isEqualByComparingTo("95.0000");
    assertThat(db.balance(db.fee)).isEqualByComparingTo("5.0000");
  }
}
