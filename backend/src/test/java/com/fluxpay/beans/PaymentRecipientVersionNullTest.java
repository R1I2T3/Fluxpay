package com.fluxpay.beans;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentRecipientVersionNullTest {
  @Test
  void legacyRowWithNullRecipientVersionLoadsAsZeroInsteadOfFailing() throws Exception {
    Payment payment =
        new Payment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new Recipient(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "A",
                "acct",
                "Bank",
                "KE",
                "KES",
                RecipientStatus.ACTIVE,
                Instant.now()),
            new BigDecimal("10.00"),
            "USD",
            "KES",
            PaymentPurpose.FAMILY_SUPPORT,
            com.fluxpay.domain.RoutePreference.BALANCED,
            "{}",
            Instant.now());
    Field field = Payment.class.getDeclaredField("recipientVersion");
    field.setAccessible(true);
    // Simulates a legacy DB row where recipient_version IS NULL (V603/V612 seeds).
    field.set(payment, null);
    assertThat(payment.recipientVersion()).isEqualTo(0L);
  }
}
