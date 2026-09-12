package com.fluxpay.service;

import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentLifecycleStatus;
import com.fluxpay.beans.Recipient;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.RecipientRepository;
import com.fluxpay.repository.WalletRepository;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DbPaymentReader implements PaymentReader {
  private final PaymentRepository payments;
  private final RecipientRepository recipients;
  private final WalletRepository wallets;

  public DbPaymentReader(
      PaymentRepository payments, RecipientRepository recipients, WalletRepository wallets) {
    this.payments = Objects.requireNonNull(payments, "payments must not be null");
    this.recipients = Objects.requireNonNull(recipients, "recipients must not be null");
    this.wallets = Objects.requireNonNull(wallets, "wallets must not be null");
  }

  @Override
  @Transactional(readOnly = true)
  public PaymentSnapshot get(String paymentId) {
    UUID id = parse(paymentId);
    Payment payment =
        payments
            .findById(id)
            .orElseThrow(() -> new NoSuchElementException("payment " + paymentId + " not found"));
    Recipient recipient =
        recipients
            .findByIdAndUserId(payment.recipientId(), payment.senderId())
            .orElseThrow(() -> new NoSuchElementException("recipient not found"));
    return new PaymentSnapshot(
        payment.id().toString(),
        payment.senderId(),
        payment.sourceWalletId(),
        wallets
            .findById(payment.sourceWalletId())
            .map(w -> w.getId())
            .orElse(payment.sourceWalletId()),
        payment.sourceAmount(),
        payment.sourceCurrency(),
        recipient.currency(),
        lifecycle(payment));
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
