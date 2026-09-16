package com.fluxpay.m5.domain;

/** Signals that a configured embedding provider could not supply a safe policy-search vector. */
public class M5EmbeddingException extends RuntimeException {
  public M5EmbeddingException(String message) {
    super(message);
  }

  public M5EmbeddingException(String message, Throwable cause) {
    super(message, cause);
  }
}
