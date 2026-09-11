package com.fluxpay.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record M5AssessmentResponse(UUID assessmentId, long assessmentSequence, UUID paymentId,
    String expectedPaymentFingerprint, UUID caseId, String risk, String screeningVerdict,
    List<M5RiskReason> reasons, Instant assessedAt, String ruleVersion, String ruleConfigHash) {
  public M5AssessmentResponse { reasons = List.copyOf(reasons); }
}
