package com.fluxpay.dto;
import com.fluxpay.beans.*; import jakarta.validation.constraints.*; import java.math.BigDecimal; import java.util.UUID;
public record DraftPaymentRequest(@NotNull UUID sourceWalletId,@NotNull UUID recipientId,@NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=15,fraction=4) BigDecimal sourceAmount,@NotBlank @Pattern(regexp="USD|EUR|INR") String sourceCurrency,@NotBlank @Pattern(regexp="USD|EUR|INR") String payoutCurrency,@NotNull PaymentPurpose purpose,@NotNull QuoteRoute preference) {}
