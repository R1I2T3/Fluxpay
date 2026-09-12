package com.fluxpay.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConfirmPaymentRequest(@NotNull UUID quoteId) {}
