package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fluxpay.dto.FxSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FxQuoteServiceTest {
  private static final Instant START = Instant.parse("2026-09-11T00:00:00Z");

  @Test
  void freshSnapshotIsSharedByPreviewAndFrozenContract() {
    MutableClock clock = new MutableClock(START);
    QueueSource source = new QueueSource(snapshot("83.50", START));
    FxQuoteService quotes = new FxQuoteService(source, clock);

    FxSnapshot preview = quotes.snapshot(" usd ", "inr");
    BigDecimal contractRate = quotes.rate("USD", "INR");

    assertEquals(new BigDecimal("83.50"), contractRate);
    assertEquals(preview, quotes.snapshot("USD", "INR"));
    assertFalse(preview.stale());
    assertEquals(1, source.calls.get());
  }

  @Test
  void refreshFailureReturnsPriorSnapshotStaleForAtMostTwoHours() {
    MutableClock clock = new MutableClock(START);
    QueueSource source = new QueueSource(snapshot("83.50", START), new FxUnavailableException());
    FxQuoteService quotes = new FxQuoteService(source, clock);
    quotes.snapshot("USD", "INR");
    clock.advance(Duration.ofMinutes(61));

    FxSnapshot fallback = quotes.snapshot("USD", "INR");

    assertEquals(new BigDecimal("83.50"), fallback.rate());
    assertEquals(START, fallback.fetchedAt());
    assertTrue(fallback.stale());
    assertEquals(2, source.calls.get());
  }

  @Test
  void refreshFailureRejectsSnapshotOlderThanTwoHours() {
    MutableClock clock = new MutableClock(START);
    QueueSource source = new QueueSource(snapshot("83.50", START), new FxUnavailableException());
    FxQuoteService quotes = new FxQuoteService(source, clock);
    quotes.snapshot("USD", "INR");
    clock.advance(Duration.ofHours(2).plusMillis(1));

    assertThrows(FxUnavailableException.class, () -> quotes.snapshot("USD", "INR"));
  }

  @Test
  void invalidOrIdenticalPairsAreRejectedBeforeTheSource() {
    QueueSource source = new QueueSource(snapshot("83.50", START));
    FxQuoteService quotes = new FxQuoteService(source, new MutableClock(START));

    assertThrows(IllegalArgumentException.class, () -> quotes.snapshot("GBP", "INR"));
    assertThrows(IllegalArgumentException.class, () -> quotes.snapshot("USD", "USD"));
    assertEquals(0, source.calls.get());
  }

  @Test
  void acceptsSnapshotTimestampedWhileTheProviderRequestIsRunning() {
    MutableClock clock = new MutableClock(START);
    FxSnapshotSource source =
        (from, to) -> {
          clock.advance(Duration.ofMillis(1));
          return new FxSnapshot(from, to, new BigDecimal("83.50"), clock.instant(), false, true);
        };
    FxQuoteService quotes = new FxQuoteService(source, clock);

    FxSnapshot result = quotes.snapshot("USD", "INR");

    assertEquals(new BigDecimal("83.50"), result.rate());
    assertEquals(START.plusMillis(1), result.fetchedAt());
    assertTrue(result.mock());
  }

  @Test
  void concurrentExpiredReadsPerformOneRefresh() throws Exception {
    MutableClock clock = new MutableClock(START);
    BlockingSource source = new BlockingSource(clock);
    FxQuoteService quotes = new FxQuoteService(source, clock);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<FxSnapshot> first = executor.submit(() -> quotes.snapshot("USD", "EUR"));
      source.entered.await();
      Future<FxSnapshot> second = executor.submit(() -> quotes.snapshot("USD", "EUR"));
      source.release.countDown();

      assertEquals(new BigDecimal("0.92"), first.get().rate());
      assertEquals(first.get(), second.get());
      assertEquals(1, source.calls.get());
    } finally {
      executor.shutdownNow();
    }
  }

  private static FxSnapshot snapshot(String rate, Instant fetchedAt) {
    return new FxSnapshot("USD", "INR", new BigDecimal(rate), fetchedAt, false, false);
  }

  private static final class QueueSource implements FxSnapshotSource {
    private final Queue<Object> results = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();

    QueueSource(Object... values) {
      for (Object value : values) {
        results.add(value);
      }
    }

    @Override
    public FxSnapshot fetch(String from, String to) {
      calls.incrementAndGet();
      Object result = results.remove();
      if (result instanceof RuntimeException exception) {
        throw exception;
      }
      FxSnapshot snapshot = (FxSnapshot) result;
      return new FxSnapshot(
          from, to, snapshot.rate(), snapshot.fetchedAt(), false, snapshot.mock());
    }
  }

  private static final class BlockingSource implements FxSnapshotSource {
    private final MutableClock clock;
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    BlockingSource(MutableClock clock) {
      this.clock = clock;
    }

    @Override
    public FxSnapshot fetch(String from, String to) {
      calls.incrementAndGet();
      entered.countDown();
      try {
        release.await();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new FxUnavailableException(exception);
      }
      return new FxSnapshot(from, to, new BigDecimal("0.92"), clock.instant(), false, false);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Duration duration) {
      instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
