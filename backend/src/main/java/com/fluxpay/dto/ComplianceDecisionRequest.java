package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ComplianceDecisionRequest(
    @NotBlank String decidedBy, @Size(max = 500) String decisionReason) {}
