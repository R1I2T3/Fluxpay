package com.fluxpay.development;

import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only simulated bank-network transfer rail.
 *
 * <p>Explicitly enabled demo behavior: active only when {@code
 * fluxpay.development.simulated-payouts-enabled=true} (default {@code false}). Normal rail
 * discovery excludes this bean unless explicitly enabled. Failure simulation via {@code
 * SIMULATE_FAILURE} is limited to this development implementation; production code paths and tests
 * use explicit fixtures instead of environment probes.
 */
@Component
@ConditionalOnProperty(
    name = "fluxpay.development.simulated-payouts-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SimulatedBankNetworkRail implements TransferRail {

  private final java.util.concurrent.ConcurrentHashMap<String, TransferRailResult> outcomes =
      new java.util.concurrent.ConcurrentHashMap<>();

  private final Supplier<String> failureProbe;
  private final java.util.concurrent.ConcurrentHashMap<
          java.util.UUID, java.util.concurrent.atomic.AtomicInteger>
      failureCounts = new java.util.concurrent.ConcurrentHashMap<>();

  public SimulatedBankNetworkRail(Supplier<String> failureProbe) {
    this.failureProbe = Objects.requireNonNull(failureProbe, "failureProbe must not be null");
  }

  public SimulatedBankNetworkRail() {
    this(() -> System.getenv("SIMULATE_FAILURE"));
  }

  @Override
  public RailType type() {
    return RailType.BANK_NETWORK;
  }

  @Override
  public Set<DestinationType> supportedDestinations() {
    return Set.of(DestinationType.EXTERNAL_ACCOUNT);
  }

  @Override
  public TransferRailResult execute(TransferRailCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    return outcomes.computeIfAbsent(command.idempotencyKey(), key -> deliver(command));
  }

  private TransferRailResult deliver(TransferRailCommand command) {
    String probe = failureProbe.get();
    if (probe == null) {
      return TransferRailResult.completed("BANK-" + command.attemptId(), command.customerFee());
    }
    // Legacy: SIMULATE_FAILURE=BANK_NETWORK fails forever (demo only).
    // New: SIMULATE_FAILURE=BANK_NETWORK:2 fails next 2 attempts per transfer, then succeeds.
    if ("BANK_NETWORK".equals(probe)) {
      return TransferRailResult.uncertain(
          "PROVIDER_TIMEOUT", "Simulated bank timeout", command.customerFee());
    }
    if (probe.startsWith("BANK_NETWORK:")) {
      int failTimes;
      try {
        failTimes = Integer.parseInt(probe.substring("BANK_NETWORK:".length()));
      } catch (NumberFormatException e) {
        return TransferRailResult.uncertain(
            "PROVIDER_TIMEOUT", "Simulated bank timeout", command.customerFee());
      }
      int seen =
          failureCounts
              .computeIfAbsent(
                  command.transferId(), k -> new java.util.concurrent.atomic.AtomicInteger())
              .incrementAndGet();
      if (seen <= failTimes) {
        return TransferRailResult.uncertain(
            "PROVIDER_TIMEOUT", "Simulated bank timeout", command.customerFee());
      }
    }
    return TransferRailResult.completed("BANK-" + command.attemptId(), command.customerFee());
  }
}
