package com.fluxpay.dto;

import java.util.UUID;

/** A policy passage that supports an extractive Compliance Copilot answer. */
public record CopilotSource(UUID policyDocumentId, String title, int chunkNumber, String excerpt) {}
