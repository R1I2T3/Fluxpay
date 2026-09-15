package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.domain.PaymentStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentResponseMapperTest {
  @Test
  void mapsPaymentAmountsIdentityAndRejectedStatus() {
    UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
    var payment = DbPaymentEligibilityGateFixture.unquotedPayment(id, UUID.randomUUID());
    payment.reject(DbPaymentEligibilityGateFixture.NOW);
    var response = PaymentResponseMapper.from(payment);
    assertThat(response.id()).isEqualTo(id);
    assertThat(response.sourceWalletId()).isEqualTo(payment.sourceWalletId());
    assertThat(response.recipientId()).isEqualTo(payment.recipientId());
    assertThat(response.sourceAmount()).isEqualTo("10.00");
    assertThat(response.sourceCurrency()).isEqualTo("USD");
    assertThat(response.payoutCurrency()).isEqualTo("KES");
    assertThat(response.status()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(response.selectedQuoteId()).isNull();
    assertThat(response.createdAt()).isEqualTo(DbPaymentEligibilityGateFixture.NOW);
  }
}
