package com.fluxpay.m5.domain;

import java.util.UUID;

/** A cited policy passage returned by a search of the active M5 vector generation. */
public record PolicyMatch(
    UUID policyChunkId,
    UUID policyDocumentId,
    String title,
    int chunkNumber,
    String content,
    double distance) {}
