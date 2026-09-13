package com.fluxpay.adapter;

import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.math.BigDecimal;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class LocalPartnerAdapter implements PayoutProvider {

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
