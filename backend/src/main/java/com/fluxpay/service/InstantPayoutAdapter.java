package com.fluxpay.service;

import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
public class InstantPayoutAdapter implements PayoutProvider {

  private static final BigDecimal EXPRESS_SURCHARGE = new BigDecimal("2.50");

  @Override
  public String code() {
    return "INSTANT_PAYOUT";
  }

  @Override
  public PayoutResult submit(PayoutCmd cmd) {
    Objects.requireNonNull(cmd, "cmd must not be null");
    return PayoutResult.ok("IP-" + UUID.randomUUID(), cmd.routeBaseFee().add(EXPRESS_SURCHARGE));
  }
}
