package com.fluxpay.dto;

import java.time.Instant;
import java.util.UUID;

public record M5ReviewCommand(UUID decisionId, UUID caseId, UUID assessmentId, UUID paymentId,
    UUID reviewReference, String paymentFingerprint, String decision, UUID reviewerId,
    Instant decidedAt, String reason) {}
