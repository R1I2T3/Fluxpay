package com.fluxpay.service;

public class DemoFundingDisabledException extends RuntimeException {
  public DemoFundingDisabledException() {
    super("Demo funding is disabled");
  }
}
