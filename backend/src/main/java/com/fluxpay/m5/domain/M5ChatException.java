package com.fluxpay.m5.domain;

/** Signals that the configured policy-answer generation provider failed safely. */
public class M5ChatException extends RuntimeException {
  public M5ChatException(String message) { super(message); }
  public M5ChatException(String message, Throwable cause) { super(message, cause); }
}
