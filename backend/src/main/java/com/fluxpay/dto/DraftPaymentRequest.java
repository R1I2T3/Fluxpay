package com.fluxpay.dto;

import com.fluxpay.beans.*;
import com.fluxpay.domain.RoutePreference;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record DraftPaymentRequest(
    @NotNull UUID sourceWalletId,
    @NotNull UUID recipientId,
    @NotBlank String sourceAmount,
    @NotBlank @Pattern(regexp = "USD|EUR|INR") String sourceCurrency,
    @NotBlank @Pattern(regexp = "USD|EUR|INR") String payoutCurrency,
    @NotNull PaymentPurpose purpose,
    @NotNull RoutePreference preference,
    @Size(max = 250) String purposeReason) {
  public DraftPaymentRequest(
      UUID sourceWalletId,
      UUID recipientId,
      String sourceAmount,
      String sourceCurrency,
      String payoutCurrency,
      PaymentPurpose purpose,
      RoutePreference preference) {
    this(
        sourceWalletId,
        recipientId,
        sourceAmount,
        sourceCurrency,
        payoutCurrency,
        purpose,
        preference,
        null);
  }

  @AssertTrue(message = "Enter a reason of 1 to 250 characters when Others is selected.")
  public boolean isPurposeReasonValid() {
    return purpose != PaymentPurpose.OTHERS
        || (purposeReason != null
            && !purposeReason.isBlank()
            && purposeReason.trim().length() <= 250);
  }
}
