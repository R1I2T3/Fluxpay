package com.fluxpay.dto;

import com.fluxpay.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaymentHoldResponse(
    UUID paymentId,
    PaymentStatus status,
    boolean onHold,
    boolean canPayout,
    Instant reviewExpiresAt,
    String risk,
    List<String> reasons,
    List<String> reasonMessages,
    String whatNext,
    String decisionReason) {}
