package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentLifecycleStatus;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.dto.PaymentPostingSnapshot;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.RecipientRepository;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DbPaymentReader implements PaymentReader {
  private final PaymentRepository payments;
  private final RecipientRepository recipients;
  private final ObjectMapper objectMapper;

  public DbPaymentReader(
      PaymentRepository payments, RecipientRepository recipients, ObjectMapper objectMapper) {
    this.payments = Objects.requireNonNull(payments, "payments must not be null");
    this.recipients = Objects.requireNonNull(recipients, "recipients must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentSnapshot get(String paymentId) {
    UUID id = parse(paymentId);
    Payment payment =
        payments
            .findById(id)
            .orElseThrow(() -> new NoSuchElementException("payment " + paymentId + " not found"));
    recipients
        .findByIdAndUserId(payment.recipientId(), payment.senderId())
        .orElseThrow(() -> new NoSuchElementException("recipient not found"));
    PaymentPostingSnapshot posting = readPosting(payment);
    return new PaymentSnapshot(
        payment.id().toString(),
        payment.senderId(),
        payment.sourceWalletId(),
        posting == null ? null : posting.clearingWalletId(),
        payment.sourceAmount(),
        payment.sourceCurrency(),
        payment.payoutCurrency(),
        lifecycle(payment),
        posting);
  }

  private PaymentPostingSnapshot readPosting(Payment payment) {
    if (payment.postingSnapshot() == null) {
      if (payment.postedAt() != null)
        throw new IllegalArgumentException("Posted payment has no original posting snapshot");
      return null;
    }
    try {
      var posting = objectMapper.readValue(payment.postingSnapshot(), PaymentPostingSnapshot.class);
      if (posting == null)
        throw new IllegalArgumentException("Original posting snapshot cannot be JSON null");
      return posting;
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Invalid original payment posting snapshot", exception);
    }
  }

  private static UUID parse(String paymentId) {
    try {
      return UUID.fromString(paymentId);
    } catch (IllegalArgumentException e) {
      throw new NoSuchElementException("payment " + paymentId + " not found");
    }
  }

  private static PaymentStatus lifecycle(Payment payment) {
    if (payment.status() == PaymentLifecycleStatus.DRAFT
        || payment.status() == PaymentLifecycleStatus.QUOTED
        || payment.status() == PaymentLifecycleStatus.PROCESSING
        || payment.status() == PaymentLifecycleStatus.UNDER_REVIEW) {
      return PaymentStatus.ROUTED;
    }
    if (payment.status() == PaymentLifecycleStatus.COMPLETED) {
      return PaymentStatus.COMPLETED;
    }
    if (payment.status() == PaymentLifecycleStatus.REFUNDED) {
      return PaymentStatus.REFUNDED;
    }
    if (payment.status() == PaymentLifecycleStatus.FAILED) {
      return PaymentStatus.FAILED;
    }
    return PaymentStatus.CREATED;
  }
}
