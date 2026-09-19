package com.fluxpay.service;

import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.exception.RequoteRequiredException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Validates a server FX snapshot at the point where conversion posting is attempted. */
@Component
public class FxQuoteValidator {
  private static final Duration FRESH_FOR = Duration.ofHours(1);

  private final Clock clock;

  public FxQuoteValidator(Clock clock) {
    this.clock = clock;
  }

  public FxSnapshot accept(FxSnapshot snapshot, String from, String to) {
    Instant now = clock.instant();
    if (snapshot == null
        || snapshot.stale()
        || !from.equals(snapshot.from())
        || !to.equals(snapshot.to())
        || snapshot.rate() == null
        || snapshot.rate().signum() <= 0
        || snapshot.fetchedAt() == null
        || snapshot.fetchedAt().isAfter(now)
        || Duration.between(snapshot.fetchedAt(), now).compareTo(FRESH_FOR) >= 0) {
      throw new RequoteRequiredException();
    }
    BigDecimal rate = snapshot.rate().setScale(8, RoundingMode.HALF_UP);
    if (rate.signum() <= 0 || rate.precision() > 19) {
      throw new RequoteRequiredException();
    }
    return new FxSnapshot(from, to, rate, snapshot.fetchedAt(), false);
  }

  public static String quoteId(FxSnapshot accepted) {
    String identity =
        accepted.from()
            + "|"
            + accepted.to()
            + "|"
            + accepted.rate().setScale(8).toPlainString()
            + "|"
            + accepted.fetchedAt();
    return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
  }
}
