package com.fluxpay.dto;

/** A newly embedded policy chunk ready to be persisted in an immutable vector generation. */
public record IndexedPolicyChunk(int chunkNumber, String content, float[] embedding) {}
