package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.ComplianceCase;
import com.fluxpay.beans.Payment;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PaymentHoldResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.ComplianceCaseRepository;
import com.fluxpay.repository.PaymentRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentHoldService {
  private static final Map<String, String> REASON_MESSAGES =
      Map.of(
          "AMOUNT_EXCEEDS_REVIEW_THRESHOLD",
          "Amount is above the routine review limit and needs a quick compliance check.",
          "FIRST_TRANSFER_TO_RECIPIENT",
          "This is your first transfer to this recipient, so it needs an extra check.",
          "RECIPIENT_ADDED_TODAY",
          "This recipient was added recently, so the transfer is held for review.",
          "INVALID_COMPLIANCE_INPUT",
          "Some payment details need an extra check before payout.",
          "UNSUPPORTED_SOURCE_CURRENCY",
          "This currency needs a manual compliance check before payout.");

  private final PaymentRepository payments;
  private final ComplianceCaseRepository cases;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public PaymentHoldService(
      PaymentRepository payments,
      ComplianceCaseRepository cases,
      ObjectMapper objectMapper,
      Clock clock) {
    this.payments = payments;
    this.cases = cases;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public PaymentHoldResponse getHold(UUID userId, UUID paymentId) {
    Payment payment =
        payments
            .findByIdAndSenderId(paymentId, userId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found."));
    if (payment.status() == PaymentStatus.UNDER_REVIEW) {
      ComplianceCase reviewCase = latestCase(payment);
      List<String> reasons = reasons(reviewCase);
      return new PaymentHoldResponse(
          payment.id(),
          payment.status(),
          true,
          false,
          payment.approvalExpiresAt(),
          reviewCase == null ? null : reviewCase.getRisk().name(),
          reasons,
          messages(reasons),
          "Your payout is on hold for compliance review. No action is needed — approval enables payout, rejection stops the transfer.",
          reviewCase == null ? null : reviewCase.getDecisionReason());
    }
    if (payment.status() == PaymentStatus.REJECTED) {
      ComplianceCase reviewCase = latestCase(payment);
      List<String> reasons = reasons(reviewCase);
      return new PaymentHoldResponse(
          payment.id(),
          payment.status(),
          false,
          false,
          null,
          reviewCase == null ? null : reviewCase.getRisk().name(),
          reasons,
          messages(reasons),
          "This payment was declined by compliance. Payout is unavailable.",
          reviewCase == null ? null : reviewCase.getDecisionReason());
    }
    return new PaymentHoldResponse(
        payment.id(),
        payment.status(),
        false,
        payment.status() == PaymentStatus.PROCESSING,
        null,
        null,
        List.of(),
        List.of(),
        payment.status() == PaymentStatus.PROCESSING
            ? "Your payment is approved. You can submit the payout."
            : "The latest payment status is shown in activity.",
        null);
  }

  private ComplianceCase latestCase(Payment payment) {
    List<ComplianceCase> rows = cases.findByPaymentIdOrderByCreatedAtDesc(payment.id());
    if (rows.isEmpty()) {
      return null;
    }
    if (payment.reviewReference() == null || payment.reviewReference().isBlank()) {
      return rows.get(0);
    }
    return rows.stream()
        .filter(c -> payment.reviewReference().equals(c.getReviewReference()))
        .findFirst()
        .orElse(rows.get(0));
  }

  private List<String> reasons(ComplianceCase reviewCase) {
    if (reviewCase == null || reviewCase.getRiskReasons() == null) {
      return List.of();
    }
    try {
      List<String> parsed =
          objectMapper.readValue(
              reviewCase.getRiskReasons(),
              objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
      return parsed == null ? List.of() : List.copyOf(parsed);
    } catch (Exception e) {
      return List.of();
    }
  }

  static List<String> messages(List<String> reasons) {
    return reasons.stream().map(r -> REASON_MESSAGES.getOrDefault(r, humanize(r))).toList();
  }

  private static String humanize(String code) {
    String lower = code.toLowerCase().replace('_', ' ');
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1) + ".";
  }
}
