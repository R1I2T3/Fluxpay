package com.fluxpay.development;

import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only simulated Standard Bank payout provider.
 *
 * <p>Explicitly enabled demo behavior: active only when {@code
 * fluxpay.development.simulated-payouts-enabled=true} (default {@code false}). Normal provider
 * discovery excludes this bean unless explicitly enabled. Failure simulation via {@code
 * SIMULATE_FAILURE} is limited to this development implementation; production code paths and tests
 * use explicit fixtures instead of environment probes.
 */
@Component
@ConditionalOnProperty(
    name = "fluxpay.development.simulated-payouts-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SimulatedStandardBankProvider implements PayoutProvider {

  private final java.util.concurrent.ConcurrentHashMap<String, PayoutResult> outcomes =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final Supplier<String> failureProbe;
  private final java.util.concurrent.ConcurrentHashMap<
          String, java.util.concurrent.atomic.AtomicInteger>
      failureCounts = new java.util.concurrent.ConcurrentHashMap<>();

  public SimulatedStandardBankProvider(Supplier<String> failureProbe) {
    this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe must not be null");
  }

  public SimulatedStandardBankProvider() {
    this(() -> System.getenv("SIMULATE_FAILURE"));
  }

  @Override
  public String code() {
    return "STANDARD_BANK";
  }

  @Override
  public PayoutResult submit(PayoutCmd cmd) {
    Objects.requireNonNull(cmd, "cmd must not be null");
    return outcomes.computeIfAbsent(cmd.idempotencyKey(), key -> execute(cmd));
  }

  private PayoutResult execute(PayoutCmd cmd) {
    String probe = failureProbe.get();
    if (probe == null) {
      return PayoutResult.ok("SB-" + cmd.attemptId(), cmd.customerFee());
    }
    // Legacy: SIMULATE_FAILURE=STANDARD_BANK fails forever (demo only).
    // New: SIMULATE_FAILURE=STANDARD_BANK:2 fails next 2 attempts per payment, then succeeds.
    if ("STANDARD_BANK".equals(probe)) {
      return PayoutResult.uncertain(
          "PROVIDER_TIMEOUT", "Simulated bank timeout", cmd.customerFee());
    }
    if (probe.startsWith("STANDARD_BANK:")) {
      int failTimes;
      try {
        failTimes = Integer.parseInt(probe.substring("STANDARD_BANK:".length()));
      } catch (NumberFormatException e) {
        return PayoutResult.uncertain(
            "PROVIDER_TIMEOUT", "Simulated bank timeout", cmd.customerFee());
      }
      int seen =
          failureCounts
              .computeIfAbsent(
                  cmd.paymentId(), k -> new java.util.concurrent.atomic.AtomicInteger())
              .incrementAndGet();
      if (seen <= failTimes) {
        return PayoutResult.uncertain(
            "PROVIDER_TIMEOUT", "Simulated bank timeout", cmd.customerFee());
      }
    }
    return PayoutResult.ok("SB-" + cmd.attemptId(), cmd.customerFee());
  }
}
