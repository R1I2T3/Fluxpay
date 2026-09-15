package com.fluxpay.exception;

import org.springframework.http.HttpStatus;

/** A durable compliance rejection is the only confirmation error intentionally committed. */
public class PaymentBlockedException extends BusinessException {
  public PaymentBlockedException() {
    super(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "PAYMENT_BLOCKED",
        "This payment was blocked by compliance.");
  }
}
