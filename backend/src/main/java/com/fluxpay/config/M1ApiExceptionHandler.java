package com.fluxpay.config;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.controller.AdminKycController;
import com.fluxpay.controller.AuthController;
import com.fluxpay.controller.KycController;
import com.fluxpay.service.M1AuthException;
import com.fluxpay.service.M1KycException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** M1-specific error mapping for authentication and KYC endpoints. */
@RestControllerAdvice(
    assignableTypes = {AuthController.class, KycController.class, AdminKycController.class})
public class M1ApiExceptionHandler {
  @ExceptionHandler(M1AuthException.class)
  public ResponseEntity<ApiError> handleAuth(M1AuthException exception) {
    HttpStatus status =
        switch (exception.getCode()) {
          case M1AuthException.INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
          case M1AuthException.EMAIL_EXISTS -> HttpStatus.CONFLICT;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status).body(error(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(M1KycException.class)
  public ResponseEntity<ApiError> handleKyc(M1KycException exception) {
    HttpStatus status =
        switch (exception.getCode()) {
          case M1KycException.KYC_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case M1KycException.REJECT_REASON_REQUIRED, M1KycException.VALIDATION ->
              HttpStatus.BAD_REQUEST;
          default -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(error(exception.getCode(), exception.getMessage()));
  }

  private ApiError error(String code, String message) {
    String correlationId = MDC.get("correlationId");
    return new ApiError(
        correlationId == null ? "none" : correlationId, code, message, Map.of(), Instant.now());
  }
}
