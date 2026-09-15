package com.fluxpay.service;

import com.fluxpay.config.M5ApiException;
import java.time.Duration;
import java.util.function.LongSupplier;

public final class M5WorkDeadline {
  private static final Duration MAX_PROVIDER_TIMEOUT = Duration.ofSeconds(3);
  private final long startedAt;
  private final long budgetNanos;
  private final String code;
  private final LongSupplier nanos;

  public M5WorkDeadline(Duration duration, String code) {
    this(duration, code, System::nanoTime);
  }

  public M5WorkDeadline(Duration duration, String code, LongSupplier nanos) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Deadline duration must be positive");
    }
    if (code == null || code.isBlank() || nanos == null) {
      throw new IllegalArgumentException("Deadline code and monotonic clock are required");
    }
    this.nanos = nanos;
    this.startedAt = nanos.getAsLong();
    this.budgetNanos = duration.toNanos();
    this.code = code;
  }

  public Duration remaining() {
    long elapsed = nanos.getAsLong() - startedAt;
    return Duration.ofNanos(Math.max(0L, budgetNanos - Math.max(0L, elapsed)));
  }

  public Duration providerTimeout(int seconds) {
    if (seconds <= 0) {
      throw new IllegalArgumentException("Provider timeout must be positive");
    }
    check();
    Duration configured = Duration.ofSeconds(seconds);
    Duration bounded = configured.compareTo(MAX_PROVIDER_TIMEOUT) > 0
        ? MAX_PROVIDER_TIMEOUT : configured;
    Duration remaining = remaining();
    return bounded.compareTo(remaining) > 0 ? remaining : bounded;
  }

  public int sqlTimeoutSeconds() {
    check();
    long millis = Math.max(1L, remaining().toMillis());
    long ceilingSeconds = Math.max(1L, (millis + 999L) / 1000L);
    return (int) Math.min(3L, ceilingSeconds);
  }

  public void check() {
    if (remaining().isZero()) {
      throw expired();
    }
  }

  public RuntimeException expired() {
    return new M5ApiException(504, code, "M5 work deadline expired");
  }
}
