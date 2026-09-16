package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.ComplianceCase;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.common.contracts.PostingPort;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.ComplianceCaseRequest;
import com.fluxpay.dto.ComplianceCaseResponse;
import com.fluxpay.dto.ComplianceDecisionRequest;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.ComplianceCaseRepository;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceCaseService {

  private final ComplianceCaseRepository repository;
  private final ObjectMapper objectMapper;
  private final PaymentRepository payments;
  private final PaymentQuoteRepository quotes;
  private final PostingPort posting;
  private final PayoutOutboxService outbox;
  private final Clock clock;

  public ComplianceCaseService(ComplianceCaseRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.payments = null;
    this.quotes = null;
    this.posting = null;
    this.outbox = null;
    this.clock = Clock.systemUTC();
  }

  @Autowired
  public ComplianceCaseService(
      ComplianceCaseRepository repository,
      ObjectMapper objectMapper,
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      PostingPort posting,
      PayoutOutboxService outbox,
      Clock clock) {
    this.repository = repository;
    this.objectMapper = objectMapper;
    this.payments = payments;
    this.quotes = quotes;
    this.posting = posting;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public ComplianceCaseResponse create(ComplianceCaseRequest request) {
    ComplianceCase entity = new ComplianceCase();
    entity.setPaymentId(request.paymentId());
    entity.setRisk(request.risk());
    entity.setStatus(ComplianceCaseStatus.OPEN);
    entity.setRiskReasons(writeJson(request.riskReasons()));
    entity.setSuggestedAction(request.suggestedAction());
    return toResponse(repository.save(entity));
  }

  @Transactional
  public ComplianceCaseResponse openReview(
      UUID paymentId, String reviewReference, com.fluxpay.common.enums.ComplianceRisk risk,
      List<String> reasons, String suggestedAction) {
    repository.findByReviewReference(reviewReference).ifPresent(existing -> {
      throw new IllegalStateException("A compliance case already exists for this review");
    });
    ComplianceCase entity = new ComplianceCase();
    entity.setPaymentId(paymentId);
    entity.setReviewReference(reviewReference);
    entity.setRisk(risk);
    entity.setStatus(ComplianceCaseStatus.OPEN);
    entity.setRiskReasons(writeJson(reasons));
    entity.setSuggestedAction(suggestedAction);
    return toResponse(repository.save(entity));
  }

  @Transactional(readOnly = true)
  public List<ComplianceCaseResponse> list(ComplianceCaseStatus status) {
    List<ComplianceCase> rows =
        status == null
            ? repository.findAllByOrderByCreatedAtDesc()
            : repository.findByStatusOrderByCreatedAtDesc(status);
    return rows.stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public ComplianceCaseResponse getById(UUID id) {
    return toResponse(find(id));
  }

  @Transactional
  public ComplianceCaseResponse approve(UUID id, ComplianceDecisionRequest request) {
    return decide(id, ComplianceCaseStatus.APPROVED, request, request.decidedBy());
  }

  @Transactional
  public ComplianceCaseResponse approve(UUID id, ComplianceDecisionRequest request, String reviewer) {
    return decide(id, ComplianceCaseStatus.APPROVED, request, reviewer);
  }

  @Transactional
  public ComplianceCaseResponse reject(UUID id, ComplianceDecisionRequest request) {
    return decide(id, ComplianceCaseStatus.REJECTED, request, request.decidedBy());
  }

  @Transactional
  public ComplianceCaseResponse reject(UUID id, ComplianceDecisionRequest request, String reviewer) {
    return decide(id, ComplianceCaseStatus.REJECTED, request, reviewer);
  }

  @Transactional
  public void deleteManualCase(UUID id) {
    ComplianceCase entity =
        repository
            .lockById(id)
            .orElseThrow(() -> new NoSuchElementException("Compliance case not found: " + id));
    if (entity.getStatus() != ComplianceCaseStatus.OPEN
        || (entity.getReviewReference() != null && !entity.getReviewReference().isBlank())) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "COMPLIANCE_CASE_DELETE_FORBIDDEN",
          "Only open manual compliance cases can be deleted.");
    }
    repository.delete(entity);
  }

  private ComplianceCaseResponse decide(
      UUID id, ComplianceCaseStatus outcome, ComplianceDecisionRequest request, String reviewer) {
    ComplianceCase entity =
        repository
            .lockById(id)
            .orElseThrow(() -> new NoSuchElementException("Compliance case not found: " + id));
    if (entity.getStatus() != ComplianceCaseStatus.OPEN) {
      throw new IllegalStateException(
          "Case " + entity.getId() + " is already " + entity.getStatus() + ", cannot re-decide");
    }
    Payment payment = activeReviewPayment(entity);
    Instant now = Instant.now(clock);
    if (payment != null) {
      if (outcome == ComplianceCaseStatus.APPROVED) {
        approvePayment(payment, now);
      } else {
        payment.reject(now);
      }
    }
    entity.setStatus(outcome);
    entity.setDecidedBy(reviewer);
    entity.setDecidedAt(now);
    entity.setDecisionReason(request.decisionReason());
    return toResponse(repository.save(entity));
  }

  private Payment activeReviewPayment(ComplianceCase complianceCase) {
    if (complianceCase.getReviewReference() == null || complianceCase.getReviewReference().isBlank()) {
      return null;
    }
    requirePaymentWorkflow();
    Payment payment =
        payments
            .lockById(complianceCase.getPaymentId())
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.CONFLICT,
                        "STALE_COMPLIANCE_REVIEW",
                        "The payment linked to this compliance review no longer exists."));
    if (payment.status() != PaymentStatus.UNDER_REVIEW
        || !Objects.equals(payment.reviewReference(), complianceCase.getReviewReference())) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "STALE_COMPLIANCE_REVIEW",
          "This compliance review is no longer active for the linked payment.");
    }
    return payment;
  }

  private void approvePayment(Payment payment, Instant now) {
    UUID quoteId = payment.selectedQuoteId();
    if (quoteId == null) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "STALE_COMPLIANCE_REVIEW",
          "The reviewed payment has no selected quote.");
    }
    PaymentQuote quote =
        quotes
            .findByIdAndPaymentId(quoteId, payment.id())
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.CONFLICT,
                        "STALE_COMPLIANCE_REVIEW",
                        "The selected quote no longer belongs to the reviewed payment."));
    if (!now.isBefore(quote.expiresAt())) {
      throw new BusinessException(
          HttpStatus.GONE, "QUOTE_EXPIRED", "The selected quote has expired during review.");
    }
    PostingAccounts accounts =
        posting.postApprovedPayment(
            payment.id(),
            payment.senderId(),
            payment.sourceWalletId(),
            payment.sourceCurrency(),
            payment.sourceAmount(),
            quote.feeAmount(),
            quote.expiresAt());
    payment.recordPosting(postingSnapshot(payment, accounts, quote), now);
    payment.selectAndProcess(quote.id(), now);
    outbox.enqueue(
        payment,
        com.fluxpay.messaging.EventTopics.PAYMENT_INITIATED,
        correlationId(),
        initiatedDetails(payment, quote));
  }

  private void requirePaymentWorkflow() {
    if (payments == null || quotes == null || posting == null || outbox == null) {
      throw new IllegalStateException("Payment review workflow is not configured");
    }
  }

  private Map<String, Object> initiatedDetails(Payment payment, PaymentQuote quote) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("status", PaymentStatus.PROCESSING.name());
    details.put("selectedQuoteId", quote.id().toString());
    details.put("senderId", payment.senderId().toString());
    details.put("walletId", payment.sourceWalletId().toString());
    details.put("sourceAmount", payment.sourceAmount().toPlainString());
    details.put("feeAmount", quote.feeAmount().toPlainString());
    details.put("netAmount", payment.sourceAmount().subtract(quote.feeAmount()).toPlainString());
    details.put("sourceCurrency", payment.sourceCurrency());
    details.put("payoutCurrency", payment.payoutCurrency());
    details.put("offeredRate", quote.offeredRate().toPlainString());
    details.put("recipientAmount", quote.recipientAmount().toPlainString());
    return details;
  }

  private String postingSnapshot(Payment payment, PostingAccounts accounts, PaymentQuote quote) {
    try {
      var node = objectMapper.createObjectNode();
      node.put("customerWalletId", accounts.customerWalletId().toString());
      node.put("clearingWalletId", accounts.clearingWalletId().toString());
      node.put("feeWalletId", accounts.feeWalletId().toString());
      node.put("currency", payment.sourceCurrency());
      node.put("gross", payment.sourceAmount().toPlainString());
      node.put("fee", quote.feeAmount().toPlainString());
      node.put("net", payment.sourceAmount().subtract(quote.feeAmount()).toPlainString());
      node.put("originalJournalReference", "payment:" + payment.id());
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "POSTING_SNAPSHOT_FAILED",
          "Posting snapshot could not be recorded.");
    }
  }

  private String correlationId() {
    String correlationId = org.slf4j.MDC.get("correlationId");
    return correlationId == null || correlationId.isBlank()
        ? UUID.randomUUID().toString()
        : correlationId;
  }

  private ComplianceCase find(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new NoSuchElementException("Compliance case not found: " + id));
  }

  private String writeJson(List<String> reasons) {
    try {
      return objectMapper.writeValueAsString(reasons);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to serialize risk reasons", e);
    }
  }

  private List<String> readJson(String json) {
    try {
      return objectMapper.readValue(
          json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception e) {
      throw new IllegalStateException("Stored risk_reasons is not valid JSON", e);
    }
  }

  private ComplianceCaseResponse toResponse(ComplianceCase e) {
    return new ComplianceCaseResponse(
        e.getId(),
        e.getPaymentId(),
        e.getReviewReference(),
        e.getRisk(),
        e.getStatus(),
        readJson(e.getRiskReasons()),
        e.getSuggestedAction(),
        e.getDecidedBy(),
        e.getDecidedAt(),
        e.getDecisionReason(),
        e.getCreatedAt());
  }
}
