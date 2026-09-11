package com.fluxpay.dto;

import java.util.UUID;

public record CopilotSource(UUID policyDocumentId, String title, int chunkNumber, String excerpt) {}
