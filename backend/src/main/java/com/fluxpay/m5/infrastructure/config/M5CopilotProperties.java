package com.fluxpay.m5.infrastructure.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "fluxpay.m5.copilot")
public record M5CopilotProperties(
    @NotBlank String chatModel,
    @DecimalMin("0.0") @DecimalMax("1.0") double chatTemperature,
    @DecimalMin("0.0") @DecimalMax("2.0") double maxDistance,
    @Positive int chatTimeoutSeconds) {}
