package com.fluxpay.service;

import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
public class StandardBankAdapter implements PayoutProvider {

  private final Supplier<String> failureProbe;

  StandardBankAdapter(Supplier<String> failureProbe) {
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
    if ("STANDARD_BANK".equals(failureProbe.get())) {
      return PayoutResult.failed("PROVIDER_TIMEOUT", "Simulated bank timeout", cmd.routeBaseFee());
    }
    return PayoutResult.ok("SB-" + UUID.randomUUID(), cmd.routeBaseFee());
  }
}
