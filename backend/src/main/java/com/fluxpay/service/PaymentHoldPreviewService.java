package com.fluxpay.service;

import com.fluxpay.beans.Recipient;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.ComplianceScreeningContext;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.dto.HoldPreviewRequest;
import com.fluxpay.dto.HoldPreviewResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.RecipientRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentHoldPreviewService {
  private final RecipientRepository recipients;
  private final ComplianceAssessor compliance;
  private final Clock clock;

  public PaymentHoldPreviewService(
      RecipientRepository recipients, ComplianceAssessor compliance, Clock clock) {
    this.recipients = recipients;
    this.compliance = compliance;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public HoldPreviewResponse preview(UUID userId, HoldPreviewRequest request) {
    Recipient recipient =
        recipients
            .findByIdAndUserId(request.recipientId(), userId)
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND", "Recipient not found."));
    BigDecimal amount;
    try {
      amount = new BigDecimal(request.sourceAmount().trim());
      if (amount.signum() <= 0) {
        throw new NumberFormatException("Amount must be positive.");
      }
    } catch (Exception e) {
      List<String> reasons = List.of("INVALID_COMPLIANCE_INPUT");
      return new HoldPreviewResponse(true, "HIGH", reasons, PaymentHoldService.messages(reasons));
    }
    Instant now = Instant.now(clock);
    var context =
        new ComplianceScreeningContext(
            request.paymentId() != null ? request.paymentId() : UUID.randomUUID(),
            userId,
            amount,
            request.sourceCurrency().toUpperCase(java.util.Locale.ROOT),
            recipient.id(),
            recipient.createdAt(),
            now);
    var assessment = compliance.assessDetailed(context);
    boolean likely =
        assessment.verdict() == ScreeningVerdict.REVIEW
            || assessment.verdict() == ScreeningVerdict.BLOCK;
    List<String> reasons = likely ? List.copyOf(assessment.reasons()) : List.of();
    return new HoldPreviewResponse(
        likely,
        likely ? assessment.risk().name() : null,
        reasons,
        PaymentHoldService.messages(reasons));
  }
}
