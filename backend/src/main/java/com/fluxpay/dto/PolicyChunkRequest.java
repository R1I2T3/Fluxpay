package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;

public record PolicyChunkRequest(@NotBlank String content) {}
