package com.fluxpay.config;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.controller.WalletController;
import com.fluxpay.service.DemoClearingWalletNotFoundException;
import com.fluxpay.service.DemoFundingDisabledException;
import com.fluxpay.service.DemoFundingRetryException;
import com.fluxpay.service.LedgerIdempotencyConflictException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WalletController.class)
public class M2ApiExceptionHandler {
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> validation(IllegalArgumentException exception) {
    return response(HttpStatus.BAD_REQUEST, "VALIDATION", exception.getMessage());
  }

  @ExceptionHandler({DemoFundingDisabledException.class, DemoClearingWalletNotFoundException.class})
  public ResponseEntity<ApiError> notFound(RuntimeException exception) {
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(LedgerIdempotencyConflictException.class)
  public ResponseEntity<ApiError> conflict(LedgerIdempotencyConflictException exception) {
    return response(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage());
  }

  @ExceptionHandler(DemoFundingRetryException.class)
  public ResponseEntity<ApiError> retry(DemoFundingRetryException exception) {
    return response(HttpStatus.CONFLICT, "RETRY", exception.getMessage());
  }

  private static ResponseEntity<ApiError> response(HttpStatus status, String code, String message) {
    String correlationId = MDC.get("correlationId");
    ApiError error =
        new ApiError(
            correlationId == null ? "none" : correlationId, code, message, Map.of(), Instant.now());
    return ResponseEntity.status(status).body(error);
  }
}
