package com.fluxpay.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
public record PolicyGuidanceRequest(@NotNull UUID complianceCaseId, @NotBlank String content) {}
