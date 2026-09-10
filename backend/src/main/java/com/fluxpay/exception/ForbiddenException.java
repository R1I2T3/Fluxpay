package com.fluxpay.exception;

/** Thrown when the current user fails an ownership/role gate; mapped to a 403 {@code ApiError}. */
public class ForbiddenException extends RuntimeException {
  public ForbiddenException(String message) {
    super(message);
  }
}
