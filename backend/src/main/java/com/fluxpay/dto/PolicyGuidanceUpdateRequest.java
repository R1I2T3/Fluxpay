package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;

public record PolicyGuidanceUpdateRequest(@NotBlank String content) {}
