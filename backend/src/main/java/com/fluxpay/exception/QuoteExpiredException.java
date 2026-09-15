package com.fluxpay.exception;

/** Thrown when the payment quote is expired or missing; mapped to a 412 {@code ApiError}. */
public class QuoteExpiredException extends RuntimeException {
  public QuoteExpiredException(String message) {
    super(message);
  }
}
