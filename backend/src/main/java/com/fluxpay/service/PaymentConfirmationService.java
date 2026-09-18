package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.beans.Recipient;
import com.fluxpay.common.contracts.ComplianceAssessment;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.ComplianceScreeningInput;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.common.contracts.PostingPort;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.ConfirmPaymentRequest;
import com.fluxpay.dto.PaymentResponse;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.exception.PaymentBlockedException;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import com.fluxpay.repository.RecipientRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentConfirmationService {
  private final PaymentRepository payments;
  private final PaymentQuoteRepository quotes;
  private final RecipientRepository recipients;
  private final KycGate kyc;
  private final ComplianceAssessor compliance;
  private final PostingPort posting;
  private final Clock clock;
  private final PaymentOperationService operations;
  private final PayoutOutboxService outbox;
  private final ObjectMapper objectMapper;
  private final PayoutRouteRepository routes;
  private final ComplianceCaseService complianceCases;

  public PaymentConfirmationService(
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      RecipientRepository recipients,
      KycGate kyc,
      ComplianceAssessor compliance,
      PostingPort posting,
      Clock clock,
      PaymentOperationService operations,
      OutboxEventRepository outboxEvents,
      OutboxDeliveryRepository deliveries,
      ObjectMapper objectMapper,
      PayoutRouteRepository routes) {
    this(
        payments,
        quotes,
        recipients,
        kyc,
        compliance,
        posting,
        clock,
        operations,
        new PayoutOutboxService(outboxEvents, deliveries, objectMapper, clock),
        objectMapper,
        routes,
        null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PaymentConfirmationService(
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      RecipientRepository recipients,
      KycGate kyc,
      ComplianceAssessor compliance,
      PostingPort posting,
      Clock clock,
      PaymentOperationService operations,
      PayoutOutboxService outbox,
      ObjectMapper objectMapper,
      PayoutRouteRepository routes,
      ComplianceCaseService complianceCases) {
    this.payments = payments;
    this.quotes = quotes;
    this.recipients = recipients;
    this.kyc = kyc;
    this.compliance = compliance;
    this.posting = posting;
    this.clock = clock;
    this.operations = operations;
    this.outbox = outbox;
    this.objectMapper = objectMapper;
    this.routes = routes;
    this.complianceCases = complianceCases;
  }

  public PaymentResponse confirm(
      UUID userId, UUID paymentId, ConfirmPaymentRequest request, String clientKey) {
    PaymentOperationService.requireKey(clientKey);
    var result =
        operations.execute(
            userId,
            clientKey,
            "CONFIRM",
            paymentId,
            normalizedConfirmRequest(userId, paymentId, request),
            PaymentResponse.class,
            () -> confirmPayment(userId, paymentId, request));
    if (result.httpStatus() == 422) throw new PaymentBlockedException();
    return result.response();
  }

  private PaymentOperationService.Result<PaymentResponse> confirmPayment(
      UUID userId, UUID paymentId, ConfirmPaymentRequest request) {
    Payment payment =
        payments
            .lockOwned(paymentId, userId)
            .orElseThrow(() -> notFound("PAYMENT_NOT_FOUND", "Payment not found."));
    if (payment.status() != PaymentStatus.QUOTED) {
      throw conflict("INVALID_PAYMENT_STATE", "Only quoted payments can be confirmed.");
    }
    PaymentQuote quote =
        quotes
            .findByIdAndPaymentId(request.quoteId(), paymentId)
            .orElseThrow(() -> notFound("QUOTE_NOT_FOUND", "Quote not found."));
    if (!Objects.equals(payment.currentQuoteGeneration(), quote.generation())) {
      throw conflict("QUOTE_SUPERSEDED", "Select a quote from the current quote generation.");
    }
    if (!Instant.now(clock).isBefore(quote.expiresAt())) {
      throw new BusinessException(
          HttpStatus.GONE, "QUOTE_EXPIRED", "The selected quote has expired.");
    }
    routes
        .findByCode(quote.route())
        .filter(r -> r.isActive())
        .orElseThrow(
            () -> conflict("ROUTE_UNAVAILABLE", "The selected quote route is unavailable."));
    Recipient recipient =
        recipients
            .lockOwned(payment.recipientId(), userId)
            .orElseThrow(() -> notFound("RECIPIENT_NOT_FOUND", "Recipient not found."));
    if (!recipient.eligible() || recipient.version() != payment.recipientVersion()) {
      throw conflict("RECIPIENT_CHANGED", "Recipient details changed; create a new draft.");
    }
    if (!kyc.isVerified(userId)) {
      throw new BusinessException(
          HttpStatus.FORBIDDEN, "KYC_NOT_VERIFIED", "KYC verification is required.");
    }
    var screeningInput =
        new ComplianceScreeningInput(
            userId, recipient.name(), payment.sourceAmount(), payment.sourceCurrency());
    ScreeningVerdict verdict = assessWithTimeout(screeningInput);
    Instant now = Instant.now(clock);
    if (verdict == ScreeningVerdict.BLOCK) {
      payment.reject(now);
      PaymentResponse blocked = response(payment);
      return new PaymentOperationService.Result<>(422, blocked, payment.id());
    }
    if (verdict == ScreeningVerdict.REVIEW) {
      ComplianceAssessment assessment = assessDetailedWithTimeout(screeningInput);
      String reviewReference = UUID.randomUUID().toString();
      payment.underReview(quote.id(), reviewReference, now);
      if (complianceCases == null) {
        throw new IllegalStateException("Compliance review case workflow is not configured");
      }
      complianceCases.openReview(
          payment.id(),
          reviewReference,
          assessment.risk(),
          assessment.reasons(),
          assessment.suggestedAction());
      outbox.enqueue(
          payment,
          com.fluxpay.messaging.EventTopics.PAYMENT_REVIEW_REQUESTED,
          correlationId(),
          reviewDetails(payment, reviewReference));
      PaymentResponse resp = response(payment);
      return new PaymentOperationService.Result<>(202, resp, payment.id());
    }
    PostingAccounts accounts =
        posting.postApprovedPayment(
            payment.id(),
            userId,
            payment.sourceWalletId(),
            payment.sourceCurrency(),
            payment.sourceAmount(),
            quote.feeAmount(),
            quote.expiresAt());
    String postingSnapshot = postingSnapshot(payment, accounts, quote);
    payment.recordPosting(postingSnapshot, now);
    payment.selectAndProcess(quote.id(), now);
    outbox.enqueue(
        payment,
        com.fluxpay.messaging.EventTopics.PAYMENT_INITIATED,
        correlationId(),
        initiatedDetails(payment, quote));
    PaymentResponse resp = response(payment);
    return new PaymentOperationService.Result<>(200, resp, payment.id());
  }

  private String correlationId() {
    String cid = org.slf4j.MDC.get("correlationId");
    if (cid != null && !cid.isBlank()) {
      return cid;
    }
    return UUID.randomUUID().toString();
  }

  private java.util.Map<String, Object> initiatedDetails(Payment payment, PaymentQuote quote) {
    java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
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

  private java.util.Map<String, Object> reviewDetails(Payment payment, String reviewReference) {
    java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
    details.put("status", PaymentStatus.UNDER_REVIEW.name());
    details.put("reviewReference", reviewReference);
    details.put("senderId", payment.senderId().toString());
    details.put("walletId", payment.sourceWalletId().toString());
    details.put("sourceAmount", payment.sourceAmount().toPlainString());
    details.put("sourceCurrency", payment.sourceCurrency());
    details.put("payoutCurrency", payment.payoutCurrency());
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

  private ScreeningVerdict assessWithTimeout(ComplianceScreeningInput input) {
    try {
      return CompletableFuture.supplyAsync(() -> compliance.assess(input)).get(3, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment timed out.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment was interrupted.");
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof BusinessException businessException) throw businessException;
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment failed.");
    }
  }

  private ComplianceAssessment assessDetailedWithTimeout(ComplianceScreeningInput input) {
    try {
      return CompletableFuture.supplyAsync(() -> compliance.assessDetailed(input))
          .get(3, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment timed out.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment was interrupted.");
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof BusinessException businessException) {
        throw businessException;
      }
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment failed.");
    }
  }

  private Object normalizedConfirmRequest(
      UUID userId, UUID paymentId, ConfirmPaymentRequest request) {
    try {
      var node = objectMapper.createObjectNode();
      node.put("operationType", "CONFIRM");
      node.put("paymentId", paymentId.toString());
      node.put("quoteId", request.quoteId().toString());
      node.put("userId", userId.toString());
      return node;
    } catch (Exception e) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Confirm request could not be normalized.");
    }
  }

  private PaymentResponse response(Payment p) {
    return PaymentResponseMapper.from(p);
  }

  private BusinessException notFound(String c, String m) {
    return new BusinessException(HttpStatus.NOT_FOUND, c, m);
  }

  private BusinessException conflict(String c, String m) {
    return new BusinessException(HttpStatus.CONFLICT, c, m);
  }
}
