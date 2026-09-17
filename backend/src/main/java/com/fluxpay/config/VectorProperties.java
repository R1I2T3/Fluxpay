package com.fluxpay.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Runtime settings for the single policy-vector space. */
@Validated
@ConfigurationProperties(prefix = "fluxpay.vector")
public record VectorProperties(
    @NotBlank String ollamaBaseUrl,
    @NotBlank String embeddingModel,
    @Positive int dimensions,
    @NotBlank String embeddingSpaceId,
    @NotBlank String chunkerVersion) {}
