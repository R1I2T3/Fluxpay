package com.fluxpay.dto;

import jakarta.validation.constraints.Size;

public record ComplianceDecisionRequest(
    String decidedBy, @Size(max = 500) String decisionReason) {}
