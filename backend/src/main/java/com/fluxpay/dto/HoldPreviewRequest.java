package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record HoldPreviewRequest(
    @NotNull UUID recipientId,
    @NotBlank String sourceAmount,
    @NotBlank String sourceCurrency,
    UUID paymentId) {}
