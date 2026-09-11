package com.fluxpay.service;

import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.dto.FxSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

@Service
public class FxQuoteService implements FxRateProvider {
  private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "INR");
  private static final Duration FRESH_FOR = Duration.ofHours(1);
  private static final Duration STALE_FOR = Duration.ofHours(2);

  private final FxSnapshotSource source;
  private final Clock clock;
  private final ConcurrentMap<Pair, FxSnapshot> snapshots = new ConcurrentHashMap<>();
  private final ConcurrentMap<Pair, Object> refreshMonitors = new ConcurrentHashMap<>();

  public FxQuoteService(FxSnapshotSource source, Clock clock) {
    this.source = source;
    this.clock = clock;
  }

  @Override
  public BigDecimal rate(String from, String to) {
    return snapshot(from, to).rate();
  }

  public FxSnapshot snapshot(String from, String to) {
    Pair pair = Pair.normalized(from, to);
    Instant now = clock.instant();
    FxSnapshot current = snapshots.get(pair);
    if (isFresh(current, now)) {
      return current.asFresh();
    }

    Object monitor = refreshMonitors.computeIfAbsent(pair, ignored -> new Object());
    synchronized (monitor) {
      now = clock.instant();
      current = snapshots.get(pair);
      if (isFresh(current, now)) {
        return current.asFresh();
      }
      try {
        FxSnapshot refreshed = validate(source.fetch(pair.from(), pair.to()), pair, now).asFresh();
        snapshots.put(pair, refreshed);
        return refreshed;
      } catch (RuntimeException exception) {
        if (isUsableStale(current, now)) {
          return current.asStale();
        }
        if (exception instanceof FxUnavailableException unavailable) {
          throw unavailable;
        }
        throw new FxUnavailableException(exception);
      }
    }
  }

  private static FxSnapshot validate(FxSnapshot snapshot, Pair pair, Instant now) {
    if (snapshot == null
        || !pair.from().equals(snapshot.from())
        || !pair.to().equals(snapshot.to())
        || snapshot.rate() == null
        || snapshot.rate().signum() <= 0
        || snapshot.fetchedAt() == null
        || snapshot.fetchedAt().isAfter(now)) {
      throw new FxUnavailableException("FX provider returned an invalid snapshot");
    }
    return snapshot;
  }

  private static boolean isFresh(FxSnapshot snapshot, Instant now) {
    return snapshot != null
        && !snapshot.fetchedAt().isAfter(now)
        && Duration.between(snapshot.fetchedAt(), now).compareTo(FRESH_FOR) < 0;
  }

  private static boolean isUsableStale(FxSnapshot snapshot, Instant now) {
    return snapshot != null
        && !snapshot.fetchedAt().isAfter(now)
        && Duration.between(snapshot.fetchedAt(), now).compareTo(STALE_FOR) <= 0;
  }

  private record Pair(String from, String to) {
    static Pair normalized(String from, String to) {
      String normalizedFrom = normalize(from);
      String normalizedTo = normalize(to);
      if (!CURRENCIES.contains(normalizedFrom) || !CURRENCIES.contains(normalizedTo)) {
        throw new IllegalArgumentException("FX pair must use USD, EUR or INR");
      }
      if (normalizedFrom.equals(normalizedTo)) {
        throw new IllegalArgumentException("FX currencies must be different");
      }
      return new Pair(normalizedFrom, normalizedTo);
    }

    private static String normalize(String value) {
      return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
  }
}
