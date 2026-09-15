package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.exception.BusinessException;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Default compliance behavior: no silent approval, explicit 503 instead. */
class UnavailableComplianceAssessorTest {
  private final UnavailableComplianceAssessor assessor = new UnavailableComplianceAssessor();

  @Test
  void assessmentFailsHonestlyWithComplianceUnavailable() {
    assertThatThrownBy(() -> assessor.assess(UUID.randomUUID(), new BigDecimal("1000.00"), "USD"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("COMPLIANCE_UNAVAILABLE");
            });
  }
}
