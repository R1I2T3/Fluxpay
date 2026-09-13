package com.fluxpay.exception;

public class OperationRetryException extends RuntimeException {
  public OperationRetryException() {
    super("The funding request conflicted with another update; retry with the same key");
  }
}
