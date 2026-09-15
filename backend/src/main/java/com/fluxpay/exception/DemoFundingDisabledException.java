package com.fluxpay.exception;

public class DemoFundingDisabledException extends RuntimeException {
  public DemoFundingDisabledException() {
    super("Demo funding is disabled");
  }
}
