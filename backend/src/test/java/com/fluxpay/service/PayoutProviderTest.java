package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PayoutProviderTest {
  private PayoutCmd command(String route, String amount, String fee) {
    return new PayoutCmd(
        "P-001", new BigDecimal(amount), "USD", "KES", route, new BigDecimal(fee), 1);
  }

  @Test
  void standardBankSucceedsWhenFailureIsNotRequested() {
    PayoutResult result =
        new StandardBankAdapter(() -> null).submit(command("STANDARD_BANK", "1000.00", "5.00"));
    assertThat(result.success()).isTrue();
    assertThat(result.providerRef()).startsWith("SB-");
  }

  @Test
  void standardBankFailureIsDeterministic() {
    PayoutResult result =
        new StandardBankAdapter(() -> "STANDARD_BANK")
            .submit(command("STANDARD_BANK", "1000.00", "5.00"));
    assertThat(result.errorCode()).isEqualTo("PROVIDER_TIMEOUT");
    assertThat(result.errorMessage()).isEqualTo("Simulated bank timeout");
  }

  @Test
  void instantAddsTwoFiftyToProviderFee() {
    PayoutResult result =
        new InstantPayoutAdapter().submit(command("INSTANT_PAYOUT", "1000.00", "8.50"));
    assertThat(result.providerFee()).isEqualByComparingTo("11.00");
  }

  @Test
  void localPartnerRejectsAmountsOverFiftyThousand() {
    PayoutResult result =
        new LocalPartnerAdapter().submit(command("LOCAL_PARTNER", "50000.01", "2.00"));
    assertThat(result.errorCode()).isEqualTo("LIMIT_EXCEEDED");
  }
}
