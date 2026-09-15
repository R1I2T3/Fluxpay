package com.fluxpay.dto;

import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.common.enums.ComplianceRisk;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ComplianceCaseResponse(
    UUID id,
    UUID paymentId,
    ComplianceRisk risk,
    ComplianceCaseStatus status,
    List<String> riskReasons,
    String suggestedAction,
    String decidedBy,
    Instant decidedAt,
    String decisionReason,
    Instant createdAt) {}
