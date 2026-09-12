package com.fluxpay.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Client-supplied KYC document metadata; no binary file is uploaded in this scope. */
public record KycFileMeta(
    @NotBlank @Size(max = 255) String fileName,
    @NotBlank @Pattern(regexp = "application/pdf|image/jpeg|image/png") String fileType,
    @Positive @Max(5 * 1024 * 1024) long fileSize) {}
