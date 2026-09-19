package com.fluxpay.common.contracts;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable payment and recipient facts used for a compliance assessment. */
public record ComplianceScreeningContext(
    UUID paymentId,
    UUID userId,
    BigDecimal amount,
    String currency,
    UUID recipientId,
    Instant recipientCreatedAt,
    Instant assessedAt) {}
