package com.fluxpay.dto;

import com.fluxpay.common.enums.PolicyCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PolicyDocumentRequest(
    @NotBlank @Size(max = 200) String title,
    @NotNull PolicyCategory category,
    @NotBlank String content) {}
