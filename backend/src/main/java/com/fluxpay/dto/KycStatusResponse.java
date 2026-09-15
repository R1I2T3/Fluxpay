package com.fluxpay.dto;

import com.fluxpay.common.enums.KycStatus;
import java.time.Instant;
import java.util.UUID;

/** Current KYC application state for a user or an admin review action. */
public record KycStatusResponse(
    UUID applicationId,
    Long version,
    KycStatus status,
    String rejectReason,
    Instant submittedAt,
    Instant decidedAt) {}
