package com.fluxpay.service;

public class FxUnavailableException extends RuntimeException {
  public FxUnavailableException() {
    super("No usable FX rate is currently available");
  }

  public FxUnavailableException(Throwable cause) {
    super("No usable FX rate is currently available", cause);
  }

  public FxUnavailableException(String message) {
    super(message);
  }
}
