package com.fluxpay.service;

import java.time.Duration;
import java.util.function.LongSupplier;

public final class M5WorkDeadline {
  public M5WorkDeadline(Duration duration, String code) {}
  public M5WorkDeadline(Duration duration, String code, LongSupplier nanos) {}
  public Duration remaining() { return Duration.ofSeconds(3); }
  public Duration providerTimeout(int seconds) { return Duration.ofSeconds(seconds); }
  public int sqlTimeoutSeconds() { return 3; }
  public void check() {}
  public RuntimeException expired() { return new IllegalStateException(); }
}
