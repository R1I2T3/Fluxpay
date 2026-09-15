package com.fluxpay.exception;

/** Domain error raised by KYC use cases. */
public class KycException extends RuntimeException {
  public static final String KYC_NOT_FOUND = "KYC_NOT_FOUND";
  public static final String KYC_ALREADY_PENDING = "KYC_ALREADY_PENDING";
  public static final String KYC_ALREADY_VERIFIED = "KYC_ALREADY_VERIFIED";
  public static final String KYC_ALREADY_DECIDED = "KYC_ALREADY_DECIDED";
  public static final String KYC_CONFLICT = "KYC_CONFLICT";
  public static final String REJECT_REASON_REQUIRED = "REJECT_REASON_REQUIRED";
  public static final String VALIDATION = "VALIDATION";
  public static final String KYC_STORAGE_UNAVAILABLE = "KYC_STORAGE_UNAVAILABLE";

  private final String code;

  public KycException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
