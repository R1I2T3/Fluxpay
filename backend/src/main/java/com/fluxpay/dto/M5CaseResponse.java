package com.fluxpay.dto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record M5CaseResponse(UUID caseId, UUID paymentId, UUID assessmentId, long assessmentSequence,
    String risk, String status, String screeningVerdict, String verdict, String paymentDisposition,
    UUID reviewReference, boolean reviewable, List<M5RiskReason> reasons, String suggestedAction,
    Instant assessedAt, UUID decidedBy, Instant decidedAt, String decisionReason,
    UUID decisionId, String deliveryState) {}
