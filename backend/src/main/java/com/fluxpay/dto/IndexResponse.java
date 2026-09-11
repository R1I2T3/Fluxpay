package com.fluxpay.dto;

import java.util.UUID;

public record IndexResponse(
    UUID policyDocumentId, int chunkCount, int dimensions, String embeddingProvider) {}
