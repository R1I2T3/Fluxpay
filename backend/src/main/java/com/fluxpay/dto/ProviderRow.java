package com.fluxpay.dto;

public record ProviderRow(
    String providerName, long totalAttempts, long completedAttempts, long failedAttempts) {}
