package com.fluxpay.dto;

import java.time.Instant;
import java.util.UUID;

public record PolicyChunkResponse(
    UUID id, UUID policyDocumentId, Integer chunkNumber, String content, Instant createdAt) {}
