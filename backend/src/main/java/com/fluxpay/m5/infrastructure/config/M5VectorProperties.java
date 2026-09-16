package com.fluxpay.m5.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Runtime settings for the single M5 policy-vector space. */
@Validated
@ConfigurationProperties(prefix = "fluxpay.m5.vector")
public record M5VectorProperties(
    @NotBlank String ollamaBaseUrl,
    @NotBlank String embeddingModel,
    @Positive int dimensions,
    @NotBlank String embeddingSpaceId,
    @NotBlank String chunkerVersion) {}
