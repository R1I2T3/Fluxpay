package com.fluxpay.service;

public class DemoFundingRetryException extends RuntimeException {
  public DemoFundingRetryException() {
    super("The funding request conflicted with another update; retry with the same key");
  }
}
