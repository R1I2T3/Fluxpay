package com.fluxpay.adapter;

import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class InstantPayoutAdapter implements PayoutProvider {

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
