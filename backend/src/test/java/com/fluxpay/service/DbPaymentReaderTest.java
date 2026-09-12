package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.QuoteRoute;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.RecipientRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DbPaymentReaderTest {
  @Test
  void mapsStoredPaymentToRoutedSnapshot() {
    PaymentRepository payments = mock(PaymentRepository.class);
    RecipientRepository recipients = mock(RecipientRepository.class);
    WalletRepository wallets = mock(WalletRepository.class);
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
            QuoteRoute.BALANCED,
            "{}",
            Instant.now());
    when(payments.findById(any())).thenReturn(Optional.of(p));
    when(recipients.findByIdAndUserId(any(), any())).thenReturn(Optional.of(r));
    when(wallets.findById(any())).thenReturn(Optional.empty());
    DbPaymentReader reader = new DbPaymentReader(payments, recipients, wallets);
    PaymentSnapshot snapshot = reader.get(paymentId.toString());
    assertEquals(paymentId.toString(), snapshot.paymentId());
    assertEquals(user, snapshot.senderUserId());
    assertEquals(wallet, snapshot.senderWalletId());
    assertEquals(0, snapshot.amount().compareTo(new BigDecimal("1000.00")));
    assertEquals("USD", snapshot.sourceCurrency());
    assertEquals("KES", snapshot.targetCurrency());
  }

  @Test
  void unknownPaymentThrowsNotFound() {
    PaymentRepository payments = mock(PaymentRepository.class);
    RecipientRepository recipients = mock(RecipientRepository.class);
    WalletRepository wallets = mock(WalletRepository.class);
    when(payments.findById(any())).thenReturn(Optional.empty());
    DbPaymentReader reader = new DbPaymentReader(payments, recipients, wallets);
    assertThrows(NoSuchElementException.class, () -> reader.get(UUID.randomUUID().toString()));
  }

  @Test
  void walletClearedAfterDeleteStillBuildsSnapshot() {
    PaymentRepository payments = mock(PaymentRepository.class);
    RecipientRepository recipients = mock(RecipientRepository.class);
    WalletRepository wallets = mock(WalletRepository.class);
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
            QuoteRoute.BALANCED,
            "{}",
            Instant.now());
    Wallet clearing = new Wallet(user, "USD", WalletAccountRole.PAYOUT_CLEARING);
    when(payments.findById(any())).thenReturn(Optional.of(p));
    when(recipients.findByIdAndUserId(any(), any())).thenReturn(Optional.of(r));
    when(wallets.findById(wallet)).thenReturn(Optional.of(clearing));
    DbPaymentReader reader = new DbPaymentReader(payments, recipients, wallets);
    assertEquals(wallet, reader.get(paymentId.toString()).senderWalletId());
  }
}
