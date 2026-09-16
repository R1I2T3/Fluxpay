package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.ComplianceCase;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.common.contracts.PostingPort;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.ComplianceDecisionRequest;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.ComplianceCaseRepository;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ComplianceCaseServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

  private final ComplianceCaseRepository cases = mock(ComplianceCaseRepository.class);
  private final PaymentRepository payments = mock(PaymentRepository.class);
  private final PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
  private final PostingPort posting = mock(PostingPort.class);
  private final PayoutOutboxService outbox = mock(PayoutOutboxService.class);

  private ComplianceCaseService service() {
    return new ComplianceCaseService(
        cases,
        new ObjectMapper().findAndRegisterModules(),
        payments,
        quotes,
        posting,
        outbox,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void rejectsAStaleReviewBeforeChangingTheCaseOrPayment() {
    UUID caseId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    ComplianceCase reviewCase = openCase(paymentId, "review-1");
    Payment payment = mock(Payment.class);
    when(payment.status()).thenReturn(PaymentStatus.PROCESSING);
    when(payment.reviewReference()).thenReturn("review-1");
    when(cases.lockById(caseId)).thenReturn(Optional.of(reviewCase));
    when(payments.lockById(paymentId)).thenReturn(Optional.of(payment));

    assertThatThrownBy(
            () ->
                service()
                    .approve(caseId, new ComplianceDecisionRequest(null, "release"), "admin@example.com"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("STALE_COMPLIANCE_REVIEW"));

    verify(reviewCase, never()).setStatus(any());
    verify(payment, never()).selectAndProcess(any(), any());
    verifyNoInteractions(posting, outbox);
  }

  @Test
  void approvalPostsAndStartsOnlyThePaymentBoundToTheOpenReview() {
    UUID caseId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    UUID senderId = UUID.randomUUID();
    UUID walletId = UUID.randomUUID();
    ComplianceCase reviewCase = openCase(paymentId, "review-1");
    Payment payment = mock(Payment.class);
    PaymentQuote quote =
        new PaymentQuote(
            quoteId,
            paymentId,
            1,
            "STANDARD_BANK",
            new BigDecimal("80"),
            BigDecimal.ZERO,
            new BigDecimal("80"),
            new BigDecimal("5.0000"),
            new BigDecimal("7600.0000"),
            30,
            true,
            NOW,
            NOW.plusSeconds(900));
    when(payment.status()).thenReturn(PaymentStatus.UNDER_REVIEW);
    when(payment.reviewReference()).thenReturn("review-1");
    when(payment.selectedQuoteId()).thenReturn(quoteId);
    when(payment.id()).thenReturn(paymentId);
    when(payment.senderId()).thenReturn(senderId);
    when(payment.sourceWalletId()).thenReturn(walletId);
    when(payment.sourceCurrency()).thenReturn("USD");
    when(payment.payoutCurrency()).thenReturn("KES");
    when(payment.sourceAmount()).thenReturn(new BigDecimal("100.0000"));
    when(cases.lockById(caseId)).thenReturn(Optional.of(reviewCase));
    when(payments.lockById(paymentId)).thenReturn(Optional.of(payment));
    when(quotes.findByIdAndPaymentId(quoteId, paymentId)).thenReturn(Optional.of(quote));
    when(posting.postApprovedPayment(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new PostingAccounts(walletId, UUID.randomUUID(), UUID.randomUUID()));
    when(cases.save(reviewCase)).thenReturn(reviewCase);

    service().approve(caseId, new ComplianceDecisionRequest(null, "release"), "admin@example.com");

    verify(payment).recordPosting(anyString(), eq(NOW));
    verify(payment).selectAndProcess(quoteId, NOW);
    verify(outbox).enqueue(eq(payment), eq("payment.initiated"), anyString(), anyMap());
    verify(reviewCase).setStatus(ComplianceCaseStatus.APPROVED);
    verify(reviewCase).setDecidedBy("admin@example.com");
  }

  @Test
  void rejectionMarksOnlyThePaymentBoundToTheOpenReviewAsRejected() {
    UUID caseId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    ComplianceCase reviewCase = openCase(paymentId, "review-1");
    Payment payment = mock(Payment.class);
    when(payment.status()).thenReturn(PaymentStatus.UNDER_REVIEW);
    when(payment.reviewReference()).thenReturn("review-1");
    when(cases.lockById(caseId)).thenReturn(Optional.of(reviewCase));
    when(payments.lockById(paymentId)).thenReturn(Optional.of(payment));
    when(cases.save(reviewCase)).thenReturn(reviewCase);

    service().reject(caseId, new ComplianceDecisionRequest(null, "risk confirmed"), "admin@example.com");

    verify(payment).reject(NOW);
    verify(reviewCase).setStatus(ComplianceCaseStatus.REJECTED);
    verify(reviewCase).setDecidedBy("admin@example.com");
    verifyNoInteractions(posting, outbox);
  }

  @Test
  void deletesAnOpenManualCase() {
    UUID caseId = UUID.randomUUID();
    ComplianceCase manualCase = new ComplianceCase();
    manualCase.setStatus(ComplianceCaseStatus.OPEN);
    when(cases.lockById(caseId)).thenReturn(Optional.of(manualCase));

    service().deleteManualCase(caseId);

    verify(cases).delete(manualCase);
  }

  @Test
  void refusesToDeleteACaseBoundToAnActivePaymentReview() {
    UUID caseId = UUID.randomUUID();
    ComplianceCase reviewCase = new ComplianceCase();
    reviewCase.setStatus(ComplianceCaseStatus.OPEN);
    reviewCase.setReviewReference("review-1");
    when(cases.lockById(caseId)).thenReturn(Optional.of(reviewCase));

    assertThatThrownBy(() -> service().deleteManualCase(caseId))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> assertThat(e.code()).isEqualTo("COMPLIANCE_CASE_DELETE_FORBIDDEN"));

    verify(cases, never()).delete(any(ComplianceCase.class));
  }

  private ComplianceCase openCase(UUID paymentId, String reference) {
    ComplianceCase reviewCase = mock(ComplianceCase.class);
    when(reviewCase.getPaymentId()).thenReturn(paymentId);
    when(reviewCase.getReviewReference()).thenReturn(reference);
    when(reviewCase.getStatus()).thenReturn(ComplianceCaseStatus.OPEN);
    when(reviewCase.getRiskReasons()).thenReturn("[\"AMOUNT_EXCEEDS_REVIEW_THRESHOLD\"]");
    return reviewCase;
  }
}
