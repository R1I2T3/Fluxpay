package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fluxpay.beans.M3OutboxDelivery;
import com.fluxpay.beans.M3PaymentOperation;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentLifecycleStatus;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.beans.Recipient;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.ConfirmPaymentRequest;
import com.fluxpay.dto.M3PostingAccounts;
import com.fluxpay.dto.PaymentResponse;
import com.fluxpay.repository.M3OutboxDeliveryRepository;
import com.fluxpay.repository.M3PaymentOperationRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.RecipientRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentConfirmationService {
  private final PaymentRepository payments;
  private final PaymentQuoteRepository quotes;
  private final RecipientRepository recipients;
  private final KycGate kyc;
  private final ComplianceAssessor compliance;
  private final M3PostingPort posting;
  private final Clock clock;
  private final M3PaymentOperationRepository operations;
  private final OutboxEventRepository outboxEvents;
  private final M3OutboxDeliveryRepository deliveries;
  private final ObjectMapper objectMapper;

  public PaymentConfirmationService(
      PaymentRepository payments,
      PaymentQuoteRepository quotes,
      RecipientRepository recipients,
      KycGate kyc,
      ComplianceAssessor compliance,
      M3PostingPort posting,
      Clock clock,
      M3PaymentOperationRepository operations,
      OutboxEventRepository outboxEvents,
      M3OutboxDeliveryRepository deliveries,
      ObjectMapper objectMapper) {
    this.payments = payments;
    this.quotes = quotes;
    this.recipients = recipients;
    this.kyc = kyc;
    this.compliance = compliance;
    this.posting = posting;
    this.clock = clock;
    this.operations = operations;
    this.outboxEvents = outboxEvents;
    this.deliveries = deliveries;
    this.objectMapper = objectMapper;
  }

  @Transactional(noRollbackFor = M3BusinessException.class)
  public PaymentResponse confirm(
      UUID userId, UUID paymentId, ConfirmPaymentRequest request, String clientKey) {
    String normalized = normalizedConfirmRequest(userId, paymentId, request);
    Payment payment =
        payments
            .lockOwned(paymentId, userId)
            .orElseThrow(() -> notFound("PAYMENT_NOT_FOUND", "Payment not found."));
    if (payment.flowVersion() != 1) {
      throw new M3BusinessException(
          HttpStatus.CONFLICT, "LEGACY_PAYMENT", "Legacy payments cannot be modified.");
    }
    Optional<M3PaymentOperation> existing =
        operations.findByUserIdAndOperationTypeAndClientKey(userId, "CONFIRM", clientKey);
    if (existing.isPresent()) {
      M3PaymentOperation op = existing.get();
      if (!op.normalizedRequest().equals(normalized)) {
        throw conflict(
            "IDEMPOTENCY_CONFLICT", "Idempotency key was already used with a different request.");
      }
      return replay(op);
    }
    if (payment.status() != PaymentLifecycleStatus.QUOTED) {
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
      throw new M3BusinessException(
          HttpStatus.GONE, "QUOTE_EXPIRED", "The selected quote has expired.");
    }
    Recipient recipient =
        recipients
            .lockOwned(payment.recipientId(), userId)
            .orElseThrow(() -> notFound("RECIPIENT_NOT_FOUND", "Recipient not found."));
    if (!recipient.eligible() || recipient.version() != payment.recipientVersion()) {
      throw conflict("RECIPIENT_CHANGED", "Recipient details changed; create a new draft.");
    }
    if (!kyc.isVerified(userId)) {
      throw new M3BusinessException(
          HttpStatus.FORBIDDEN, "KYC_NOT_VERIFIED", "KYC verification is required.");
    }
    ScreeningVerdict verdict = assessWithTimeout(userId, payment);
    Instant now = Instant.now(clock);
    if (verdict == ScreeningVerdict.BLOCK) {
      payment.reject(now);
      PaymentResponse blocked =
          new PaymentResponse(
              payment.id(),
              payment.sourceWalletId(),
              payment.recipientId(),
              payment.sourceAmount().toPlainString(),
              payment.sourceCurrency(),
              payment.payoutCurrency(),
              PaymentLifecycleStatus.REJECTED,
              payment.selectedQuoteId(),
              payment.createdAt(),
              false);
      storeOperation(userId, clientKey, normalized, 422, blocked, payment.id());
      throw new M3BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PAYMENT_BLOCKED",
          "This payment was blocked by compliance.");
    }
    if (verdict == ScreeningVerdict.REVIEW) {
      String reviewReference = UUID.randomUUID().toString();
      payment.underReview(reviewReference, now);
      int sequence = payment.nextEventSequence();
      persistOutbox(
          payment,
          sequence,
          "payment.review.requested",
          reviewPayload(payment, sequence, reviewReference, now),
          now);
      PaymentResponse resp = response(payment);
      storeOperation(userId, clientKey, normalized, 202, resp, payment.id());
      return resp;
    }
    M3PostingAccounts accounts =
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
    int sequence = payment.nextEventSequence();
    persistOutbox(
        payment,
        sequence,
        "payment.initiated",
        initiatedPayload(payment, quote, sequence, now),
        now);
    PaymentResponse resp = response(payment);
    storeOperation(userId, clientKey, normalized, 200, resp, payment.id());
    return resp;
  }

  /** Backwards-compatible overload. */
  @Transactional(noRollbackFor = M3BusinessException.class)
  public PaymentResponse confirm(UUID userId, UUID paymentId, ConfirmPaymentRequest request) {
    return confirm(userId, paymentId, request, "legacy-" + UUID.randomUUID());
  }

  private void persistOutbox(
      Payment payment, int sequence, String topic, String payload, Instant now) {
    UUID eventId = UUID.randomUUID();
    OutboxEvent event = new OutboxEvent(eventId, topic, payload, now);
    outboxEvents.save(event);
    M3OutboxDelivery delivery = new M3OutboxDelivery(eventId, payment.id(), sequence, now);
    deliveries.save(delivery);
  }

  private String initiatedPayload(Payment payment, PaymentQuote quote, int sequence, Instant now) {
    try {
      ObjectNode node = objectMapper.createObjectNode();
      UUID eventId = UUID.randomUUID();
      node.put("eventId", eventId.toString());
      node.put("eventType", "payment.initiated.v1");
      node.put("aggregateSequence", sequence);
      node.put("paymentId", payment.id().toString());
      node.put("status", PaymentLifecycleStatus.PROCESSING.name());
      node.put("selectedQuoteId", quote.id().toString());
      node.put("senderId", payment.senderId().toString());
      node.put("walletId", payment.sourceWalletId().toString());
      node.put("sourceAmount", payment.sourceAmount().toPlainString());
      node.put("feeAmount", quote.feeAmount().toPlainString());
      node.put("netAmount", payment.sourceAmount().subtract(quote.feeAmount()).toPlainString());
      node.put("sourceCurrency", payment.sourceCurrency());
      node.put("payoutCurrency", payment.payoutCurrency());
      node.put("offeredRate", quote.offeredRate().toPlainString());
      node.put("recipientAmount", quote.recipientAmount().toPlainString());
      node.put("occurredAt", now.toString());
      node.put("schemaVersion", "payment.initiated.v1");
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "OUTBOX_STORE_FAILED",
          "Initiated event could not be stored.");
    }
  }

  private String reviewPayload(Payment payment, int sequence, String reviewReference, Instant now) {
    try {
      ObjectNode node = objectMapper.createObjectNode();
      UUID eventId = UUID.randomUUID();
      node.put("eventId", eventId.toString());
      node.put("eventType", "payment.review.requested.v1");
      node.put("aggregateSequence", sequence);
      node.put("paymentId", payment.id().toString());
      node.put("status", PaymentLifecycleStatus.UNDER_REVIEW.name());
      node.put("reviewReference", reviewReference);
      node.put("senderId", payment.senderId().toString());
      node.put("walletId", payment.sourceWalletId().toString());
      node.put("sourceAmount", payment.sourceAmount().toPlainString());
      node.put("sourceCurrency", payment.sourceCurrency());
      node.put("payoutCurrency", payment.payoutCurrency());
      node.put("occurredAt", now.toString());
      node.put("schemaVersion", "payment.review.requested.v1");
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "OUTBOX_STORE_FAILED",
          "Review event could not be stored.");
    }
  }

  private String postingSnapshot(Payment payment, M3PostingAccounts accounts, PaymentQuote quote) {
    try {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("customerWalletId", accounts.customerWalletId().toString());
      node.put("clearingWalletId", accounts.clearingWalletId().toString());
      node.put("feeWalletId", accounts.feeWalletId().toString());
      node.put("currency", payment.sourceCurrency());
      node.put("gross", payment.sourceAmount().toPlainString());
      node.put("fee", quote.feeAmount().toPlainString());
      node.put("net", payment.sourceAmount().subtract(quote.feeAmount()).toPlainString());
      var keys = objectMapper.createArrayNode();
      keys.add("m3:" + payment.id() + ":customer");
      keys.add("m3:" + payment.id() + ":clearing");
      keys.add("m3:" + payment.id() + ":fee");
      node.set("keys", keys);
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "POSTING_SNAPSHOT_FAILED",
          "Posting snapshot could not be recorded.");
    }
  }

  private ScreeningVerdict assessWithTimeout(UUID userId, Payment payment) {
    try {
      return CompletableFuture.supplyAsync(
              () -> compliance.assess(userId, payment.sourceAmount(), payment.sourceCurrency()))
          .get(3, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      throw new M3BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment timed out.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new M3BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment was interrupted.");
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof M3BusinessException mbe) {
        throw mbe;
      }
      throw new M3BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COMPLIANCE_UNAVAILABLE",
          "Compliance assessment failed.");
    }
  }

  private void storeOperation(
      UUID userId,
      String clientKey,
      String normalized,
      int outcomeStatus,
      PaymentResponse resp,
      UUID paymentId) {
    String responseJson;
    try {
      responseJson = objectMapper.writeValueAsString(resp);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "IDEMPOTENCY_STORE_FAILED",
          "Idempotency response could not be stored.");
    }
    M3PaymentOperation op =
        new M3PaymentOperation(
            UUID.randomUUID(),
            userId,
            "CONFIRM",
            clientKey,
            normalized,
            outcomeStatus,
            responseJson,
            paymentId,
            Instant.now(clock));
    try {
      operations.saveAndFlush(op);
    } catch (DataIntegrityViolationException race) {
      M3PaymentOperation winner =
          operations
              .findByUserIdAndOperationTypeAndClientKey(userId, "CONFIRM", clientKey)
              .orElseThrow(() -> race);
      if (!winner.normalizedRequest().equals(normalized)) {
        throw conflict(
            "IDEMPOTENCY_CONFLICT", "Idempotency key was already used with a different request.");
      }
      throw new M3BusinessException(
          HttpStatus.CONFLICT, "RETRY", "A concurrent confirmation won; replay the winner.");
    }
  }

  private String normalizedConfirmRequest(
      UUID userId, UUID paymentId, ConfirmPaymentRequest request) {
    try {
      var node = objectMapper.createObjectNode();
      node.put("operationType", "CONFIRM");
      node.put("paymentId", paymentId.toString());
      node.put("quoteId", request.quoteId().toString());
      node.put("userId", userId.toString());
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Confirm request could not be normalized.");
    }
  }

  private PaymentResponse replay(M3PaymentOperation op) {
    if (op.outcomeStatus() == 422) {
      throw new M3BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PAYMENT_BLOCKED",
          "This payment was blocked by compliance.");
    }
    try {
      return objectMapper.readValue(op.responseData(), PaymentResponse.class);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "IDEMPOTENCY_REPLAY_FAILED",
          "Stored idempotency response could not be read.");
    }
  }

  private PaymentResponse response(Payment p) {
    return new PaymentResponse(
        p.id(),
        p.sourceWalletId(),
        p.recipientId(),
        p.sourceAmount().toPlainString(),
        p.sourceCurrency(),
        p.payoutCurrency(),
        p.status(),
        p.selectedQuoteId(),
        p.createdAt(),
        false);
  }

  private M3BusinessException notFound(String c, String m) {
    return new M3BusinessException(HttpStatus.NOT_FOUND, c, m);
  }

  private M3BusinessException conflict(String c, String m) {
    return new M3BusinessException(HttpStatus.CONFLICT, c, m);
  }
}
