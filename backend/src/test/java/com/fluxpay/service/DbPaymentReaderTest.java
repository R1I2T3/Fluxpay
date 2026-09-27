package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.RecipientRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DbPaymentReaderTest {
  @Test
  void reconstructsDistinctOriginalPostingAccounts() {
    PaymentRepository payments = mock(PaymentRepository.class);
    RecipientRepository recipients = mock(RecipientRepository.class);
    UUID paymentId = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    Recipient r =
        new Recipient(
            UUID.randomUUID(),
            user,
            "A",
            "acct",
            "Bank",
            "KE",
            "KES",
            RecipientStatus.ACTIVE,
            Instant.now());
    Payment p =
        new Payment(
            paymentId,
            user,
            wallet,
            r,
            new BigDecimal("1000.00"),
            "USD",
            "KES",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.BALANCED,
            "{\"name\":\"A\",\"account\":\"acct\",\"bankName\":\"Bank\",\"country\":\"KE\",\"currency\":\"KES\"}",
            Instant.now());
    when(payments.findById(any())).thenReturn(Optional.of(p));
    UUID clearingId = UUID.randomUUID();
    UUID feeId = UUID.randomUUID();
    p.recordPosting(
        """
        {"customerWalletId":"%s","clearingWalletId":"%s","feeWalletId":"%s",
         "currency":"USD","gross":"1000.0000","net":"950.0000","fee":"50.0000",
         "originalJournalReference":"payment:%s"}
        """
            .formatted(wallet, clearingId, feeId, paymentId),
        Instant.now());
    when(recipients.findByIdAndUserId(any(), any())).thenReturn(Optional.of(r));
    DbPaymentReader reader = new DbPaymentReader(payments, recipients, new ObjectMapper());
    PaymentSnapshot snapshot = reader.get(paymentId.toString());
    assertEquals(paymentId.toString(), snapshot.paymentId());
    assertEquals(user, snapshot.senderUserId());
    assertEquals(wallet, snapshot.senderWalletId());
    assertEquals(clearingId, snapshot.payoutClearingWalletId());
    assertEquals(feeId, snapshot.posting().feeWalletId());
    assertEquals(0, snapshot.amount().compareTo(new BigDecimal("1000.00")));
    assertEquals("USD", snapshot.sourceCurrency());
    assertEquals("KES", snapshot.targetCurrency());
  }

  @Test
  void unknownPaymentThrowsNotFound() {
    PaymentRepository payments = mock(PaymentRepository.class);
    RecipientRepository recipients = mock(RecipientRepository.class);
    when(payments.findById(any())).thenReturn(Optional.empty());
    DbPaymentReader reader = new DbPaymentReader(payments, recipients, new ObjectMapper());
    assertThrows(NoSuchElementException.class, () -> reader.get(UUID.randomUUID().toString()));
  }

  @Test
  void unpostedPaymentHasNoInventedClearingWallet() {
    PaymentRepository payments = mock(PaymentRepository.class);
    RecipientRepository recipients = mock(RecipientRepository.class);
    UUID paymentId = UUID.randomUUID();
    UUID user = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    Recipient r =
        new Recipient(
            UUID.randomUUID(),
            user,
            "A",
            "acct",
            "Bank",
            "KE",
            "KES",
            RecipientStatus.ACTIVE,
            Instant.now());
    Payment p =
        new Payment(
            paymentId,
            user,
            wallet,
            r,
            new BigDecimal("1000.00"),
            "USD",
            "KES",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.BALANCED,
            "{\"name\":\"A\",\"account\":\"acct\",\"bankName\":\"Bank\",\"country\":\"KE\",\"currency\":\"KES\"}",
            Instant.now());
    when(payments.findById(any())).thenReturn(Optional.of(p));
    when(recipients.findByIdAndUserId(any(), any())).thenReturn(Optional.of(r));
    DbPaymentReader reader = new DbPaymentReader(payments, recipients, new ObjectMapper());
    assertEquals(wallet, reader.get(paymentId.toString()).senderWalletId());
    assertEquals("DRAFT", reader.get(paymentId.toString()).status().name());
    p.quoted(1, Instant.now());
    assertEquals("QUOTED", reader.get(paymentId.toString()).status().name());
    p.underReview(Instant.now());
    assertEquals("UNDER_REVIEW", reader.get(paymentId.toString()).status().name());
    p.reject(Instant.now());
    assertEquals("REJECTED", reader.get(paymentId.toString()).status().name());
    p.cancel(Instant.now());
    assertEquals("CANCELLED", reader.get(paymentId.toString()).status().name());
    assertNull(reader.get(paymentId.toString()).payoutClearingWalletId());
    assertNull(reader.get(paymentId.toString()).posting());
  }
}
