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
    Instant decidedAt,
    java.util.List<KycFileMeta> documents) {
  public KycStatusResponse(
      UUID applicationId,
      Long version,
      KycStatus status,
      String rejectReason,
      Instant submittedAt,
      Instant decidedAt) {
    this(applicationId, version, status, rejectReason, submittedAt, decidedAt, java.util.List.of());
  }
}
