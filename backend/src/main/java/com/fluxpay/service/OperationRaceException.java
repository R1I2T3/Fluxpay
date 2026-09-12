package com.fluxpay.service;

public class OperationRaceException extends RuntimeException {
  public OperationRaceException() {
    super("The wallet operation is already being processed");
  }
}
