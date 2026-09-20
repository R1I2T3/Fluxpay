package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.common.contracts.ComplianceScreeningContext;
import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.ComplianceProperties;
import com.fluxpay.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentRiskComplianceAssessorTest {
  private final PaymentRepository payments = mock(PaymentRepository.class);
  private final PaymentRiskComplianceAssessor assessor =
      new PaymentRiskComplianceAssessor(
          new ComplianceProperties(Map.of("USD", new BigDecimal("10000")), 24), payments);

  @Test
  void createsHighRiskEvidenceForANewRecipientFirstHighValueTransfer() {
    UUID paymentId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-18T10:00:00Z");
    when(payments.existsPriorSubmittedPaymentForRecipient(userId, recipientId, paymentId))
        .thenReturn(false);

    var assessment =
        assessor.assessDetailed(
            new ComplianceScreeningContext(
                paymentId,
                userId,
                new BigDecimal("12500"),
                "USD",
                recipientId,
                now.minus(4, ChronoUnit.HOURS),
                now));

    assertThat(assessment.verdict()).isEqualTo(ScreeningVerdict.REVIEW);
    assertThat(assessment.risk()).isEqualTo(ComplianceRisk.HIGH);
    assertThat(assessment.reasons())
        .containsExactly(
            "AMOUNT_EXCEEDS_REVIEW_THRESHOLD",
            "FIRST_TRANSFER_TO_RECIPIENT",
            "RECIPIENT_ADDED_TODAY");
  }

  @Test
  void createsMediumRiskEvidenceForAFirstTransferToAnEstablishedRecipient() {
    UUID paymentId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    Instant now = Instant.parse("2026-09-18T10:00:00Z");
    when(payments.existsPriorSubmittedPaymentForRecipient(userId, recipientId, paymentId))
        .thenReturn(false);

    var assessment =
        assessor.assessDetailed(
            new ComplianceScreeningContext(
                paymentId,
                userId,
                new BigDecimal("250"),
                "USD",
                recipientId,
                now.minus(3, ChronoUnit.DAYS),
                now));

    assertThat(assessment.verdict()).isEqualTo(ScreeningVerdict.REVIEW);
    assertThat(assessment.risk()).isEqualTo(ComplianceRisk.MEDIUM);
    assertThat(assessment.reasons()).containsExactly("FIRST_TRANSFER_TO_RECIPIENT");
  }
}
