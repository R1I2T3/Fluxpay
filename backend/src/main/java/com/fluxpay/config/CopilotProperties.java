package com.fluxpay.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "fluxpay.copilot")
public record CopilotProperties(
    @NotBlank String chatModel,
    @DecimalMin("0.0") @DecimalMax("1.0") double chatTemperature,
    @DecimalMin("0.0") @DecimalMax("2.0") double maxDistance,
    @Positive int chatTimeoutSeconds,
    @DefaultValue("true") boolean reasoningEnabled,
    @DefaultValue("30m") @NotBlank String chatKeepAlive,
    @DefaultValue("512") @Positive int chatMaxTokens,
    @DefaultValue("2048") @Positive int chatContextTokens,
    @DefaultValue("3") @Positive int maxSources) {}
