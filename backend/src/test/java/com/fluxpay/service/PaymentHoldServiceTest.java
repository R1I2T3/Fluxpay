package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.ComplianceCase;
import com.fluxpay.beans.Payment;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.ComplianceCaseRepository;
import com.fluxpay.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentHoldServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID PAYMENT_ID = UUID.randomUUID();

  private final PaymentRepository payments = mock(PaymentRepository.class);
  private final ComplianceCaseRepository cases = mock(ComplianceCaseRepository.class);

  private PaymentHoldService service() {
    return new PaymentHoldService(
        payments,
        cases,
        new ObjectMapper().findAndRegisterModules(),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void returnsUserSafeHoldWithWhyAndExpiryForOwnedUnderReviewPayment() {
    Payment payment = DbPaymentEligibilityGateFixture.storedPayment(PAYMENT_ID, USER_ID, 1);
    UUID quoteId = UUID.randomUUID();
    Instant expiresAt = NOW.plusSeconds(24 * 60 * 60);
    payment.underReview(quoteId, "review-1", expiresAt, DbPaymentEligibilityGateFixture.NOW);

    ComplianceCase reviewCase = new ComplianceCase();
    reviewCase.setPaymentId(PAYMENT_ID);
    reviewCase.setReviewReference("review-1");
    reviewCase.setRisk(ComplianceRisk.MEDIUM);
    reviewCase.setStatus(ComplianceCaseStatus.OPEN);
    reviewCase.setRiskReasons(
        "[\"AMOUNT_EXCEEDS_REVIEW_THRESHOLD\",\"FIRST_TRANSFER_TO_RECIPIENT\"]");
    reviewCase.setSuggestedAction("Hold payment for manual compliance review before payout.");

    when(payments.findByIdAndSenderId(PAYMENT_ID, USER_ID)).thenReturn(Optional.of(payment));
    when(cases.findByPaymentIdOrderByCreatedAtDesc(PAYMENT_ID)).thenReturn(List.of(reviewCase));

    var hold = service().getHold(USER_ID, PAYMENT_ID);

    assertThat(hold.paymentId()).isEqualTo(PAYMENT_ID);
    assertThat(hold.status()).isEqualTo(PaymentStatus.UNDER_REVIEW);
    assertThat(hold.onHold()).isTrue();
    assertThat(hold.canPayout()).isFalse();
    assertThat(hold.reviewExpiresAt()).isEqualTo(expiresAt);
    assertThat(hold.reasons())
        .containsExactly("AMOUNT_EXCEEDS_REVIEW_THRESHOLD", "FIRST_TRANSFER_TO_RECIPIENT");
    assertThat(hold.reasonMessages()).hasSize(2);
    assertThat(String.join(" ", hold.reasonMessages())).containsIgnoringCase("first transfer");
    assertThat(hold.whatNext()).containsIgnoringCase("review");
  }

  @Test
  void rejectsNonOwnedPayment() {
    when(payments.findByIdAndSenderId(PAYMENT_ID, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().getHold(USER_ID, PAYMENT_ID))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("PAYMENT_NOT_FOUND"));
  }

  @Test
  void returnsNotOnHoldForProcessingPayment() {
    Payment payment = DbPaymentEligibilityGateFixture.storedPayment(PAYMENT_ID, USER_ID, 1);
    payment.selectAndProcess(UUID.randomUUID(), DbPaymentEligibilityGateFixture.NOW);
    when(payments.findByIdAndSenderId(PAYMENT_ID, USER_ID)).thenReturn(Optional.of(payment));

    var hold = service().getHold(USER_ID, PAYMENT_ID);

    assertThat(hold.onHold()).isFalse();
    assertThat(hold.canPayout()).isTrue();
    assertThat(hold.reasons()).isEmpty();
  }
}
