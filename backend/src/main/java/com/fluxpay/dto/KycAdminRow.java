package com.fluxpay.dto;

import com.fluxpay.beans.KycDocumentType;
import com.fluxpay.common.enums.KycStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Complete, single-case snapshot used by the admin KYC review queue. */
public record KycAdminRow(
    UUID applicationId,
    long version,
    String email,
    String fullName,
    KycDocumentType docType,
    String docNumber,
    KycStatus status,
    Instant submittedAt,
    Instant decidedAt,
    String rejectReason,
    List<KycFileMeta> documents) {}
