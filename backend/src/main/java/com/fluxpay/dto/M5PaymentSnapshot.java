package com.fluxpay.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One authoritative, consistent observation. Null risk data means unavailable. */
public record M5PaymentSnapshot(UUID paymentId, UUID senderId, UUID walletId, UUID recipientId,
    @JsonSerialize(using = ToStringSerializer.class) BigDecimal sourceAmount,
    String sourceCurrency, String payoutCurrency, String purpose, boolean purposeAvailable,
    String recipientSnapshot, long recipientVersion, String recipientCountry,
    Boolean kycVerified, Boolean priorCompletedPayment, Long recipientTodayCount,
    Instant observedAt, Instant historyCutoff, Instant recipientDayStart, String dayZone,
    String recipientTodayMode) {}
