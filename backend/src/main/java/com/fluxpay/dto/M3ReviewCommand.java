package com.fluxpay.dto;
import java.time.Instant;
import java.util.UUID;
/** Complete trusted command identity; unrelated end-user APIs cannot submit this command. */
public record M3ReviewCommand(UUID decisionId, UUID caseId, UUID assessmentId, UUID paymentId,
    UUID reviewReference, String paymentFingerprint, String decision, UUID reviewerId,
    Instant decidedAt, String reason) {}
