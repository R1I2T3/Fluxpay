package com.fluxpay.exception;

/** Domain error raised by authentication use cases. */
public class AuthException extends RuntimeException {
  public static final String EMAIL_EXISTS = "EMAIL_EXISTS";
  public static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

  private final String code;

  public AuthException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
