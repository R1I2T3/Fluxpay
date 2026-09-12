package com.fluxpay.service;

/** Domain error raised by M1 KYC use cases. */
public class M1KycException extends RuntimeException {
  public static final String KYC_NOT_FOUND = "KYC_NOT_FOUND";
  public static final String KYC_ALREADY_PENDING = "KYC_ALREADY_PENDING";
  public static final String KYC_ALREADY_VERIFIED = "KYC_ALREADY_VERIFIED";
  public static final String KYC_ALREADY_DECIDED = "KYC_ALREADY_DECIDED";
  public static final String KYC_CONFLICT = "KYC_CONFLICT";
  public static final String REJECT_REASON_REQUIRED = "REJECT_REASON_REQUIRED";
  public static final String VALIDATION = "VALIDATION";

  private final String code;

  public M1KycException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
