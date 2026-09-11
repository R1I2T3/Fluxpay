package com.fluxpay.beans;
import java.util.UUID;
public record M5PolicyChunk(UUID id, UUID policyDocumentId, UUID generationId,
    int chunkNumber, String content, float[] embedding) {}
