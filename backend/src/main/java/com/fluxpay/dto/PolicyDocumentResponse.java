package com.fluxpay.dto;

import com.fluxpay.common.enums.PolicyCategory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PolicyDocumentResponse(
    UUID id,
    String title,
    PolicyCategory category,
    String content,
    String documentHash,
    Instant createdAt,
    List<PolicyChunkResponse> chunks) {}
