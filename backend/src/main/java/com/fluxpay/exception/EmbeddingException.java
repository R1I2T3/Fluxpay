package com.fluxpay.exception;

/** Signals that a configured embedding provider could not supply a safe policy-search vector. */
public class EmbeddingException extends RuntimeException {
  public EmbeddingException(String message) {
    super(message);
  }

  public EmbeddingException(String message, Throwable cause) {
    super(message, cause);
  }
}
