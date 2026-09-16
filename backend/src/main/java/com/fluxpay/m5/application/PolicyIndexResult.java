package com.fluxpay.m5.application;

import java.util.UUID;

/** Summary of a successful, immutable policy-vector generation publication. */
public record PolicyIndexResult(UUID policyDocumentId, int chunkCount) {}
