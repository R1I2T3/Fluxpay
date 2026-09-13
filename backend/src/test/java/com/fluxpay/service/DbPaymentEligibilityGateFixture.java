package com.fluxpay.service;

import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.domain.RoutePreference;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

class DbPaymentEligibilityGateFixture {
  static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

  static Payment storedPayment(UUID paymentId, UUID userId, int generation) {
    Payment payment =
        new Payment(
            paymentId,
            userId,
            UUID.randomUUID(),
            recipient(userId),
            new BigDecimal("10.00"),
            "USD",
            "KES",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.BALANCED,
            "{}",
            NOW);
    payment.quoted(generation, NOW);
    return payment;
  }

  static Payment unquotedPayment(UUID paymentId, UUID userId) {
    return new Payment(
        paymentId,
        userId,
        UUID.randomUUID(),
        recipient(userId),
        new BigDecimal("10.00"),
        "USD",
        "KES",
        PaymentPurpose.FAMILY_SUPPORT,
        RoutePreference.BALANCED,
        "{}",
        NOW);
  }

  static Recipient recipient(UUID userId) {
    return new Recipient(
        UUID.randomUUID(), userId, "A", "acct", "Bank", "KE", "KES", RecipientStatus.ACTIVE, NOW);
  }

  static PaymentQuote quote(UUID paymentId, int generation, String route) {
    return new PaymentQuote(
        UUID.randomUUID(),
        paymentId,
        generation,
        route,
        new BigDecimal("80.000000"),
        BigDecimal.ZERO,
        new BigDecimal("80.000000"),
        new BigDecimal("5.00"),
        new BigDecimal("400.0000"),
        240,
        true,
        NOW,
        NOW.plusSeconds(900));
  }
}
