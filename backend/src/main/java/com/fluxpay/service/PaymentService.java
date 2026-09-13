package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentLifecycleStatus;
import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.beans.Recipient;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.common.contracts.WalletPort;
import com.fluxpay.dto.DraftPaymentRequest;
import com.fluxpay.dto.PaymentPageResponse;
import com.fluxpay.dto.PaymentResponse;
import com.fluxpay.dto.WalletSnapshot;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.RecipientRepository;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
  private final PaymentRepository payments;
  private final RecipientRepository recipients;
  private final WalletPort wallets;
  private final KycGate kyc;
  private final Clock clock;
  private final PaymentOperationRepository operations;
  private final ObjectMapper objectMapper;

  public PaymentService(
      PaymentRepository payments,
      RecipientRepository recipients,
      WalletPort wallets,
      KycGate kyc,
      Clock clock,
      PaymentOperationRepository operations,
      ObjectMapper objectMapper) {
    this.payments = payments;
    this.recipients = recipients;
    this.wallets = wallets;
    this.kyc = kyc;
    this.clock = clock;
    this.operations = operations;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public PaymentResponse draft(UUID userId, DraftPaymentRequest request, String clientKey) {
    String normalized = normalizedDraftRequest(userId, request);
    Optional<PaymentOperation> existing =
        operations.findByUserIdAndOperationTypeAndClientKey(userId, "DRAFT", clientKey);
    if (existing.isPresent()) {
      PaymentOperation op = existing.get();
      if (!op.normalizedRequest().equals(normalized)) {
        throw new BusinessException(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_CONFLICT",
            "Idempotency key was already used with a different request.");
      }
      return replay(op);
    }
    if (request.sourceCurrency().equals(request.payoutCurrency())) {
      throw invalid("INVALID_CORRIDOR", "Source and payout currencies must differ.");
    }
    Recipient r =
        recipients
            .lockOwned(request.recipientId(), userId)
            .orElseThrow(() -> notFound("RECIPIENT_NOT_FOUND", "Recipient not found."));
    if (!r.eligible()) {
      throw invalid("RECIPIENT_UNAVAILABLE", "Recipient is blocked or incomplete.");
    }
    if (!r.currency().equals(request.payoutCurrency())) {
      throw invalid("CURRENCY_MISMATCH", "Payout currency must match the recipient.");
    }
    if (!kyc.isVerified(userId)) {
      throw new BusinessException(
          HttpStatus.FORBIDDEN, "KYC_NOT_VERIFIED", "KYC verification is required.");
    }
    WalletSnapshot wallet =
        wallets
            .findOwned(userId, request.sourceWalletId())
            .orElseThrow(() -> notFound("WALLET_NOT_FOUND", "Wallet not found."));
    if (!wallet.customerEligible() || !wallet.currency().equals(request.sourceCurrency())) {
      throw invalid("WALLET_UNAVAILABLE", "Wallet is not eligible for this payment.");
    }
    java.math.BigDecimal requestedAmount = parseAmount(request.sourceAmount());
    if (wallet.availableFunds().compareTo(requestedAmount) < 0) {
      throw invalid("INSUFFICIENT_FUNDS", "Available funds are insufficient.");
    }
    java.math.BigDecimal canonicalAmount;
    try {
      canonicalAmount = requestedAmount.setScale(4, RoundingMode.UNNECESSARY);
    } catch (ArithmeticException e) {
      throw invalid("INVALID_AMOUNT", "Amount must have at most 4 decimal places.");
    }
    String snapshot =
        "{\"name\":\""
            + escape(r.name())
            + "\",\"account\":\""
            + escape(r.account())
            + "\",\"bankName\":\""
            + escape(r.bankName())
            + "\",\"country\":\""
            + r.country()
            + "\",\"currency\":\""
            + r.currency()
            + "\"}";
    Payment p =
        new Payment(
            UUID.randomUUID(),
            userId,
            request.sourceWalletId(),
            r,
            canonicalAmount,
            request.sourceCurrency(),
            request.payoutCurrency(),
            request.purpose(),
            request.preference(),
            snapshot,
            Instant.now(clock));
    Payment saved = payments.save(p);
    PaymentResponse resp = response(saved);
    String responseJson = toJson(resp);
    PaymentOperation op =
        new PaymentOperation(
            UUID.randomUUID(),
            userId,
            "DRAFT",
            clientKey,
            normalized,
            201,
            responseJson,
            saved.id(),
            Instant.now(clock));
    try {
      operations.saveAndFlush(op);
    } catch (DataIntegrityViolationException race) {
      PaymentOperation winner =
          operations
              .findByUserIdAndOperationTypeAndClientKey(userId, "DRAFT", clientKey)
              .orElseThrow(() -> race);
      if (!winner.normalizedRequest().equals(normalized)) {
        throw new BusinessException(
            HttpStatus.CONFLICT,
            "IDEMPOTENCY_CONFLICT",
            "Idempotency key was already used with a different request.");
      }
      return replay(winner);
    }
    return resp;
  }

  @Transactional
  public PaymentResponse cancel(UUID userId, UUID id) {
    Payment p =
        payments
            .lockOwned(id, userId)
            .orElseThrow(() -> notFound("PAYMENT_NOT_FOUND", "Payment not found."));
    if (p.flowVersion() != 1) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "LEGACY_PAYMENT", "Legacy payments cannot be modified.");
    }
    if (p.status() == PaymentLifecycleStatus.CANCELLED) {
      return response(p);
    }
    if (p.status() != PaymentLifecycleStatus.DRAFT && p.status() != PaymentLifecycleStatus.QUOTED) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "INVALID_PAYMENT_STATE",
          "Only draft or quoted payments can be cancelled.");
    }
    p.cancel(Instant.now(clock));
    return response(p);
  }

  @Transactional(readOnly = true)
  public PaymentPageResponse list(UUID userId, int page, int size) {
    Page<Payment> result =
        payments.findBySenderIdAndFlowVersionOrderByCreatedAtDescIdDesc(
            userId, 1, PageRequest.of(page, Math.min(Math.max(size, 1), 100)));
    return new PaymentPageResponse(
        result.getContent().stream().map(this::response).toList(),
        page,
        result.getSize(),
        result.getTotalElements());
  }

  @Transactional(readOnly = true)
  public PaymentResponse detail(UUID userId, UUID id) {
    Payment p =
        payments
            .findByIdAndSenderId(id, userId)
            .orElseThrow(() -> notFound("PAYMENT_NOT_FOUND", "Payment not found."));
    return response(p);
  }

  private PaymentResponse response(Payment p) {
    return PaymentResponseMapper.from(p);
  }

  private java.math.BigDecimal parseAmount(String raw) {
    if (raw == null || raw.isBlank()) {
      throw invalid("INVALID_AMOUNT", "Amount is required.");
    }
    try {
      java.math.BigDecimal parsed = new java.math.BigDecimal(raw.trim());
      if (parsed.signum() <= 0) {
        throw invalid("INVALID_AMOUNT", "Amount must be positive.");
      }
      return parsed;
    } catch (NumberFormatException e) {
      throw invalid("INVALID_AMOUNT", "Amount must be a valid decimal string.");
    }
  }

  private String normalizedDraftRequest(UUID userId, DraftPaymentRequest request) {
    String amount;
    try {
      amount =
          parseAmount(request.sourceAmount()).setScale(4, RoundingMode.UNNECESSARY).toPlainString();
    } catch (ArithmeticException e) {
      throw invalid("INVALID_AMOUNT", "Amount must have at most 4 decimal places.");
    }
    try {
      var node = objectMapper.createObjectNode();
      node.put("operationType", "DRAFT");
      node.put("payoutCurrency", request.payoutCurrency().toUpperCase());
      node.put("preference", request.preference().name());
      node.put("purpose", request.purpose().name());
      node.put("recipientId", request.recipientId().toString());
      node.put("sourceAmount", amount);
      node.put("sourceCurrency", request.sourceCurrency().toUpperCase());
      node.put("sourceWalletId", request.sourceWalletId().toString());
      node.put("userId", userId.toString());
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      if (e instanceof BusinessException mbe) {
        throw mbe;
      }
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Draft request could not be normalized.");
    }
  }

  private PaymentResponse replay(PaymentOperation op) {
    try {
      return objectMapper.readValue(op.responseData(), PaymentResponse.class);
    } catch (Exception e) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "IDEMPOTENCY_REPLAY_FAILED",
          "Stored idempotency response could not be read.");
    }
  }

  private String toJson(PaymentResponse resp) {
    try {
      return objectMapper.writeValueAsString(resp);
    } catch (Exception e) {
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "IDEMPOTENCY_STORE_FAILED",
          "Idempotency response could not be stored.");
    }
  }

  private String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private BusinessException invalid(String c, String m) {
    return new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, c, m);
  }

  private BusinessException notFound(String c, String m) {
    return new BusinessException(HttpStatus.NOT_FOUND, c, m);
  }
}
