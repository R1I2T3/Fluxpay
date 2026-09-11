package com.fluxpay.service;

import com.fluxpay.common.enums.ComplianceRisk;
import com.fluxpay.dto.ComplianceAssessRequest;
import com.fluxpay.dto.ComplianceCaseResponse;
import com.fluxpay.repository.ComplianceLookupRepository;
import com.fluxpay.repository.ComplianceLookupRepository.PaymentFacts;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic, explainable Payment Passport rule engine (product spec section 10.4). Every rule
 * that fires contributes a plain-text reason code -- there is no black-box score, and every
 * decision can be read straight back off the resulting {@code compliance_cases} row.
 *
 * <p>{@code paymentPurpose} and {@code highRiskDestination} are optional caller-supplied hints,
 * not database lookups: the current {@code payments}/{@code recipients} schema (V301) has no
 * purpose or destination-country column yet. When a hint is omitted, that rule is simply skipped
 * rather than penalizing missing data nobody can actually supply today.
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Service
public class ComplianceAssessmentService {

  /** Fixed demo FX table so "amount exceeds threshold" works across currencies without calling
   *  the real FX provider (that's the wallet module's job, not compliance's). Rough, non-live
   *  rates -- good enough to classify risk, not to move money. */
  private static final Map<String, BigDecimal> USD_RATE =
      Map.of(
          "USD", BigDecimal.ONE,
          "EUR", new BigDecimal("1.08"),
          "INR", new BigDecimal("0.012"));

  private final ComplianceLookupRepository lookupRepository;
  private final ComplianceCaseService caseService;
  private final BigDecimal highValueThresholdUsd;

  public ComplianceAssessmentService(
      ComplianceLookupRepository lookupRepository,
      ComplianceCaseService caseService,
      @Value("${fluxpay.compliance-high-value-threshold-usd:1000}") String highValueThresholdUsd) {
    this.lookupRepository = lookupRepository;
    this.caseService = caseService;
    this.highValueThresholdUsd = new BigDecimal(highValueThresholdUsd);
  }

  @Transactional
  public ComplianceCaseResponse assess(UUID paymentId, ComplianceAssessRequest hints) {
    PaymentFacts facts =
        lookupRepository
            .findPaymentFacts(paymentId)
            .orElseThrow(() -> new NoSuchElementException("Payment not found: " + paymentId));

    List<String> highReasons = new ArrayList<>();
    List<String> mediumReasons = new ArrayList<>();

    if (!lookupRepository.isKycVerified(facts.payerUserId())) {
      highReasons.add("KYC_UNVERIFIED");
    }

    long priorPayments =
        lookupRepository.countPriorPaymentsToRecipient(facts.recipientId(), paymentId);
    if (priorPayments == 0) {
      mediumReasons.add("FIRST_TRANSFER_TO_RECIPIENT");
    }

    Instant recipientCreatedAt = lookupRepository.recipientCreatedAt(facts.recipientId());
    if (recipientCreatedAt != null
        && Duration.between(recipientCreatedAt, facts.createdAt()).toHours() < 24) {
      mediumReasons.add("RECIPIENT_RECENTLY_ADDED");
    }

    if (toUsd(facts.amount(), facts.currency()).compareTo(highValueThresholdUsd) > 0) {
      mediumReasons.add("AMOUNT_EXCEEDS_THRESHOLD");
    }

    if (hints != null) {
      String purpose = hints.paymentPurpose();
      if (purpose != null && purpose.trim().length() < 5) {
        mediumReasons.add("PAYMENT_PURPOSE_MISSING");
      }
      if (Boolean.TRUE.equals(hints.highRiskDestination())) {
        highReasons.add("HIGH_RISK_DESTINATION");
      }
    }

    ComplianceRisk risk;
    if (!highReasons.isEmpty()) {
      risk = ComplianceRisk.HIGH;
    } else if (mediumReasons.size() >= 2) {
      risk = ComplianceRisk.MEDIUM;
    } else {
      risk = ComplianceRisk.LOW;
    }

    List<String> allReasons = new ArrayList<>(highReasons);
    allReasons.addAll(mediumReasons);
    if (allReasons.isEmpty()) {
      allReasons.add("RECIPIENT_KNOWN");
    }

    return caseService.createFromAssessment(
        paymentId, risk, allReasons, suggestedActionFor(risk, allReasons));
  }

  private BigDecimal toUsd(BigDecimal amount, String currency) {
    return amount.multiply(USD_RATE.getOrDefault(currency, BigDecimal.ONE));
  }

  private String suggestedActionFor(ComplianceRisk risk, List<String> reasons) {
    if (reasons.contains("KYC_UNVERIFIED")) {
      return "Hold payment and request the customer complete KYC verification.";
    }
    if (reasons.contains("HIGH_RISK_DESTINATION")) {
      return "Route to manual compliance review for destination screening.";
    }
    if (reasons.contains("FIRST_TRANSFER_TO_RECIPIENT")
        || reasons.contains("RECIPIENT_RECENTLY_ADDED")) {
      return "Confirm recipient bank details before payout.";
    }
    if (reasons.contains("AMOUNT_EXCEEDS_THRESHOLD")) {
      return "Confirm source of funds before releasing payout.";
    }
    if (reasons.contains("PAYMENT_PURPOSE_MISSING")) {
      return "Request additional payment purpose detail from the customer.";
    }
    return risk == ComplianceRisk.LOW
        ? "No action required; auto-eligible for release."
        : "Route to manual compliance review.";
  }
}
