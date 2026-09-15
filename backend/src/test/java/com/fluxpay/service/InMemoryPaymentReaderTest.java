package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.config.InMemoryPaymentReader;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class InMemoryPaymentReaderTest {
  private final InMemoryPaymentReader reader = new InMemoryPaymentReader();

  @Test
  void exposesTheP001DemoPayment() {
    PaymentSnapshot payment = reader.get("P-001");
    assertThat(payment.amount()).isEqualByComparingTo(new BigDecimal("1000.00"));
    assertThat(payment.sourceCurrency()).isEqualTo("USD");
    assertThat(payment.targetCurrency()).isEqualTo("KES");
    assertThat(payment.senderWalletId()).isNotEqualTo(payment.payoutClearingWalletId());
  }

  @Test
  void exposesTheP002DemoPayment() {
    PaymentSnapshot payment = reader.get("P-002");
    assertThat(payment.amount()).isEqualByComparingTo(new BigDecimal("500.00"));
    assertThat(payment.sourceCurrency()).isEqualTo("USD");
    assertThat(payment.targetCurrency()).isEqualTo("KES");
    assertThat(payment.senderWalletId()).isNotEqualTo(payment.payoutClearingWalletId());
  }

  @Test
  void unknownPaymentIsNotFound() {
    assertThatThrownBy(() -> reader.get("P-404"))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("payment P-404 not found");
  }
}
