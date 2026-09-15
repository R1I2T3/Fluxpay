package com.fluxpay.service;

/** Domain error raised by M1 authentication use cases. */
public class M1AuthException extends RuntimeException {
  public static final String EMAIL_EXISTS = "EMAIL_EXISTS";
  public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

  private final String code;

  public M1AuthException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
