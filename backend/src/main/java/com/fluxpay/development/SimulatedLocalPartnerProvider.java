package com.fluxpay.development;

import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development-only simulated local partner payout provider.
 *
 * <p>Active only when {@code fluxpay.development.simulated-payouts-enabled=true} (default {@code
 * false}). Normal provider discovery excludes this bean unless explicitly enabled.
 */
@Component
@ConditionalOnProperty(
    name = "fluxpay.development.simulated-payouts-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SimulatedLocalPartnerProvider implements PayoutProvider {

  private static final BigDecimal LIMIT = new BigDecimal("50000");

  @Override
  public String code() {
    return "LOCAL_PARTNER";
  }

  @Override
  public PayoutResult submit(PayoutCmd cmd) {
    Objects.requireNonNull(cmd, "cmd must not be null");
    if (cmd.amount().compareTo(LIMIT) > 0) {
      return PayoutResult.failed(
          "LIMIT_EXCEEDED", "Amount exceeds local partner limit", cmd.customerFee());
    }
    return PayoutResult.ok("LP-" + cmd.attemptId(), cmd.customerFee());
  }
}
