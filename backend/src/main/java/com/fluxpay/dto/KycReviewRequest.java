package com.fluxpay.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Review input for approving or rejecting a KYC application. */
public record KycReviewRequest(
    @NotNull @PositiveOrZero Long expectedVersion, @Size(max = 500) String reason) {}
