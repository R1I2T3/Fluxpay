package com.fluxpay.dto;

import com.fluxpay.beans.*;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record DraftPaymentRequest(
    @NotNull UUID sourceWalletId,
    @NotNull UUID recipientId,
    @NotBlank String sourceAmount,
    @NotBlank @Pattern(regexp = "USD|EUR|INR") String sourceCurrency,
    @NotBlank @Pattern(regexp = "USD|EUR|INR") String payoutCurrency,
    @NotNull PaymentPurpose purpose,
    @NotNull QuoteRoute preference) {}
