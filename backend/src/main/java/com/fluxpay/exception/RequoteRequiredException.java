package com.fluxpay.exception;

import org.springframework.http.HttpStatus;

public class RequoteRequiredException extends BusinessException {
  public RequoteRequiredException() {
    super(
        HttpStatus.CONFLICT,
        "REQUOTE_REQUIRED",
        "The FX quote is no longer valid; request a fresh quote");
  }
}
