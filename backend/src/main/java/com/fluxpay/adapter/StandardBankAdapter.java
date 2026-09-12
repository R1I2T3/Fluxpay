package com.fluxpay.adapter;

import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.service.PayoutProvider;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
public class StandardBankAdapter implements PayoutProvider {

  private final Supplier<String> failureProbe;
  private final java.util.concurrent.ConcurrentHashMap<
          String, java.util.concurrent.atomic.AtomicInteger>
      failureCounts = new java.util.concurrent.ConcurrentHashMap<>();

  public StandardBankAdapter(Supplier<String> failureProbe) {
    this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe must not be null");
  }

  public StandardBankAdapter() {
    this(() -> System.getenv("SIMULATE_FAILURE"));
  }

  @Override
  public String code() {
    return "STANDARD_BANK";
  }

  @Override
  public PayoutResult submit(PayoutCmd cmd) {
    Objects.requireNonNull(cmd, "cmd must not be null");
    String probe = failureProbe.get();
    if (probe == null) {
      return PayoutResult.ok("SB-" + UUID.randomUUID(), cmd.routeBaseFee());
    }
    // Legacy: SIMULATE_FAILURE=STANDARD_BANK fails forever (demo only).
    // New: SIMULATE_FAILURE=STANDARD_BANK:2 fails next 2 attempts per payment, then succeeds.
    if ("STANDARD_BANK".equals(probe)) {
      return PayoutResult.failed("PROVIDER_TIMEOUT", "Simulated bank timeout", cmd.routeBaseFee());
    }
    if (probe.startsWith("STANDARD_BANK:")) {
      int failTimes;
      try {
        failTimes = Integer.parseInt(probe.substring("STANDARD_BANK:".length()));
      } catch (NumberFormatException e) {
        return PayoutResult.failed(
            "PROVIDER_TIMEOUT", "Simulated bank timeout", cmd.routeBaseFee());
      }
      int seen =
          failureCounts
              .computeIfAbsent(
                  cmd.paymentId(), k -> new java.util.concurrent.atomic.AtomicInteger())
              .incrementAndGet();
      if (seen <= failTimes) {
        return PayoutResult.failed(
            "PROVIDER_TIMEOUT", "Simulated bank timeout", cmd.routeBaseFee());
      }
    }
    return PayoutResult.ok("SB-" + UUID.randomUUID(), cmd.routeBaseFee());
  }
}
