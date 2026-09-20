package com.fluxpay.dto;

public record BankLinkRequest(String bankName, String accountLast4, String currency) {
  // The application normally ignores unknown JSON fields. This endpoint must not accept
  // full account numbers (or other banking secrets) as ignored extra properties.
  @com.fasterxml.jackson.annotation.JsonAnySetter
  public void rejectAdditionalField(String name, Object value) {
    throw new IllegalArgumentException(
        "Bank link accepts only bankName, accountLast4 and currency");
  }
}
