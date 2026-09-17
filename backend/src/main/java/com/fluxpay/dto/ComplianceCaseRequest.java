package com.fluxpay.dto;

import com.fluxpay.common.enums.ComplianceRisk;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ComplianceCaseRequest(
    @NotNull UUID paymentId,
    @NotNull ComplianceRisk risk,
    @NotEmpty List<@NotBlank String> riskReasons,
    @NotBlank @Size(max = 400) String suggestedAction) {}
