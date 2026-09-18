package com.fluxpay.development;

import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only simulated real-time-network transfer rail.
 *
 * <p>Active only when {@code fluxpay.development.simulated-payouts-enabled=true} (default {@code
 * false}). Normal rail discovery excludes this bean unless explicitly enabled. Delivery succeeds
 * deterministically.
 */
@Component
@ConditionalOnProperty(
    name = "fluxpay.development.simulated-payouts-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SimulatedRealTimeNetworkRail implements TransferRail {

  @Override
  public RailType type() {
    return RailType.REAL_TIME_NETWORK;
  }

  @Override
  public Set<DestinationType> supportedDestinations() {
    return Set.of(DestinationType.EXTERNAL_ACCOUNT);
  }

  @Override
  public TransferRailResult execute(TransferRailCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    return TransferRailResult.completed("REALTIME-" + command.attemptId(), command.customerFee());
  }
}
