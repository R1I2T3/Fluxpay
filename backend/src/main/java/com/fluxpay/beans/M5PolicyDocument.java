package com.fluxpay.beans;
import java.time.Instant;
import java.util.UUID;
public record M5PolicyDocument(UUID id, String title, String category, String content,
    String documentHash, Instant createdAt, long version, String indexState,
    UUID activeGenerationId, String embeddingSpaceId, String chunkerVersion, int chunkCount) {}
