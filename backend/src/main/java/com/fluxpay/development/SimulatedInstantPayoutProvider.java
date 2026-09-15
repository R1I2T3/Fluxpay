package com.fluxpay.development;

import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only simulated instant payout provider.
 *
 * <p>Active only when {@code fluxpay.development.simulated-payouts-enabled=true} (default {@code
 * false}). Normal provider discovery excludes this bean unless explicitly enabled.
 */
@Component
@ConditionalOnProperty(
    name = "fluxpay.development.simulated-payouts-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SimulatedInstantPayoutProvider implements PayoutProvider {

  @Override
  public String code() {
    return "INSTANT_PAYOUT";
  }

  @Override
  public PayoutResult submit(PayoutCmd cmd) {
    Objects.requireNonNull(cmd, "cmd must not be null");
    return PayoutResult.ok("IP-" + cmd.attemptId(), cmd.customerFee());
  }
}
