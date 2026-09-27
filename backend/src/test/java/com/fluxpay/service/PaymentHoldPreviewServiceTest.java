package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.config.ComplianceProperties;
import com.fluxpay.dto.HoldPreviewRequest;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.RecipientRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentHoldPreviewServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID RECIPIENT_ID = UUID.randomUUID();

  private PaymentHoldPreviewService service(Recipient recipient, boolean priorSubmitted) {
    var recipients = mock(RecipientRepository.class);
    var payments = mock(PaymentRepository.class);
    when(recipients.findByIdAndUserId(RECIPIENT_ID, USER_ID)).thenReturn(Optional.of(recipient));
    when(payments.existsPriorSubmittedPaymentForRecipient(any(), any(), any()))
        .thenReturn(priorSubmitted);
    var properties = new ComplianceProperties(Map.of("USD", new BigDecimal("10000")), 24);
    var compliance = new PaymentRiskComplianceAssessor(properties, payments);
    return new PaymentHoldPreviewService(recipients, compliance, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private static Recipient recipient(Instant createdAt) {
    return new Recipient(
        RECIPIENT_ID,
        USER_ID,
        "Jamie",
        "acct-1",
        "Bank",
        "KE",
        "KES",
        RecipientStatus.ACTIVE,
        createdAt);
  }

  @Test
  void firstTransferToLongKnownRecipientIsLikelyHeld() {
    var response =
        service(recipient(NOW.minusSeconds(30 * 24 * 3600)), false)
            .preview(
                USER_ID, new HoldPreviewRequest(RECIPIENT_ID, "10.00", "USD", UUID.randomUUID()));

    assertThat(response.likely()).isTrue();
    assertThat(response.reasons()).contains("FIRST_TRANSFER_TO_RECIPIENT");
    assertThat(String.join(" ", response.reasonMessages())).containsIgnoringCase("first transfer");
  }

  @Test
  void repeatSmallTransferToKnownRecipientIsNotLikelyHeld() {
    var response =
        service(recipient(NOW.minusSeconds(30 * 24 * 3600)), true)
            .preview(
                USER_ID, new HoldPreviewRequest(RECIPIENT_ID, "10.00", "USD", UUID.randomUUID()));

    assertThat(response.likely()).isFalse();
    assertThat(response.reasons()).isEmpty();
  }

  @Test
  void rejectsRecipientOwnedBySomeoneElse() {
    var recipients = mock(RecipientRepository.class);
    when(recipients.findByIdAndUserId(RECIPIENT_ID, USER_ID)).thenReturn(Optional.empty());
    var properties = new ComplianceProperties(Map.of("USD", new BigDecimal("10000")), 24);
    var compliance = new PaymentRiskComplianceAssessor(properties, mock(PaymentRepository.class));
    var preview =
        new PaymentHoldPreviewService(recipients, compliance, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                preview.preview(
                    USER_ID, new HoldPreviewRequest(RECIPIENT_ID, "10.00", "USD", null)))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("RECIPIENT_NOT_FOUND"));
  }
}
