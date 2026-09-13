package com.fluxpay.exception;

import com.fluxpay.beans.WalletAccountRole;
import org.springframework.http.HttpStatus;

public class SystemAccountUnavailableException extends BusinessException {
  public SystemAccountUnavailableException(String currency, WalletAccountRole role) {
    super(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SYSTEM_ACCOUNT_UNAVAILABLE",
        "A unique system account is unavailable for " + currency + " / " + role);
  }

  public SystemAccountUnavailableException() {
    super(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SYSTEM_ACCOUNT_UNAVAILABLE",
        "fluxpay.system-user-id is not configured");
  }
}
