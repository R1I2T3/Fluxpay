package com.fluxpay.dto;

import java.util.UUID;

/** A cited policy passage returned by a search of the active vector generation. */
public record PolicyMatch(
    UUID policyChunkId,
    UUID policyDocumentId,
    String title,
    int chunkNumber,
    String content,
    double distance) {}
