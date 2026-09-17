package com.fluxpay.exception;

/** Signals that the configured policy-answer generation provider failed safely. */
public class ChatException extends RuntimeException {
  public ChatException(String message) {
    super(message);
  }

  public ChatException(String message, Throwable cause) {
    super(message, cause);
  }
}
