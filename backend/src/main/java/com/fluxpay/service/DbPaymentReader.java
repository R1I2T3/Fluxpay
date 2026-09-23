package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.domain.ExternalAccountDestination;
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
  private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

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
    ExternalAccountDestination destination =
        parseDestination(payment.recipientSnapshot(), payment.payoutCurrency());
    return new PaymentSnapshot(
        payment.id().toString(),
        payment.senderId(),
        payment.sourceWalletId(),
        posting == null ? null : posting.clearingWalletId(),
        payment.sourceAmount(),
        payment.sourceCurrency(),
        payment.payoutCurrency(),
        payment.status(),
        posting,
        destination);
  }

  /**
   * Parses the frozen recipient snapshot into the execution-time destination. The snapshot country
   * and currency are validated against the payment's payout currency so quotes and recommendations
   * always price the frozen destination.
   */
  static ExternalAccountDestination parseDestination(String snapshotJson, String payoutCurrency) {
    if (snapshotJson == null || snapshotJson.isBlank()) {
      throw new IllegalArgumentException("Payment has no frozen recipient snapshot");
    }
    JsonNode node;
    try {
      node = SNAPSHOT_MAPPER.readTree(snapshotJson);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("Invalid frozen recipient snapshot", e);
    }
    String account = text(node, "account");
    String bankName = text(node, "bankName");
    String country = text(node, "country");
    String currency = text(node, "currency");
    if (account == null || account.isBlank()) {
      throw new IllegalArgumentException("Frozen recipient snapshot has no account");
    }
    if (country == null || country.isBlank()) {
      throw new IllegalArgumentException("Frozen recipient snapshot has no country");
    }
    if (currency == null || currency.isBlank()) {
      throw new IllegalArgumentException("Frozen recipient snapshot has no currency");
    }
    if (payoutCurrency != null
        && !payoutCurrency.isBlank()
        && !currency.equalsIgnoreCase(payoutCurrency.trim())) {
      throw new IllegalArgumentException(
          "Frozen recipient currency does not match this payment's payout currency");
    }
    return new ExternalAccountDestination(account, bankName, country, currency);
  }

  private static String text(JsonNode node, String field) {
    JsonNode child = node == null ? null : node.get(field);
    return child == null || child.isNull() ? null : child.asText();
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
}
