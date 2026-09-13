package com.fluxpay.exception;

import org.springframework.http.HttpStatus;

public class SystemAccountUnavailableException extends BusinessException {
  public SystemAccountUnavailableException() {
    super(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SYSTEM_ACCOUNT_UNAVAILABLE",
        "fluxpay.system-user-id is not configured");
  }
}
