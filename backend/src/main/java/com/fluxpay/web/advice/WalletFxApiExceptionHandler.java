package com.fluxpay.web.advice;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.common.web.ApiErrorFactory;
import com.fluxpay.controller.FxController;
import com.fluxpay.controller.WalletController;
import com.fluxpay.exception.DemoClearingWalletNotFoundException;
import com.fluxpay.exception.DemoFundingDisabledException;
import com.fluxpay.exception.FxSystemWalletNotFoundException;
import com.fluxpay.exception.FxUnavailableException;
import com.fluxpay.exception.InsufficientWalletFundsException;
import com.fluxpay.exception.LedgerIdempotencyConflictException;
import com.fluxpay.exception.OperationRetryException;
import com.fluxpay.exception.RequoteRequiredException;
import com.fluxpay.exception.WalletNotFoundException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {WalletController.class, FxController.class})
public class WalletFxApiExceptionHandler {
  @ExceptionHandler(com.fluxpay.exception.SystemAccountUnavailableException.class)
  public ResponseEntity<ApiError> systemAccountUnavailable(
      com.fluxpay.exception.SystemAccountUnavailableException exception) {
    return response(exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> validation(IllegalArgumentException exception) {
    return response(HttpStatus.BAD_REQUEST, "VALIDATION", exception.getMessage());
  }

  @ExceptionHandler({
    DemoFundingDisabledException.class,
    DemoClearingWalletNotFoundException.class,
    WalletNotFoundException.class
  })
  public ResponseEntity<ApiError> notFound(RuntimeException exception) {
    return response(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
  }

  @ExceptionHandler(LedgerIdempotencyConflictException.class)
  public ResponseEntity<ApiError> conflict(LedgerIdempotencyConflictException exception) {
    return response(HttpStatus.CONFLICT, "CONFLICT", exception.getMessage());
  }

  @ExceptionHandler(OperationRetryException.class)
  public ResponseEntity<ApiError> retry(OperationRetryException exception) {
    return response(HttpStatus.CONFLICT, "RETRY", exception.getMessage());
  }

  @ExceptionHandler(RequoteRequiredException.class)
  public ResponseEntity<ApiError> requoteRequired(RequoteRequiredException exception) {
    return response(exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler(InsufficientWalletFundsException.class)
  public ResponseEntity<ApiError> insufficientFunds(InsufficientWalletFundsException exception) {
    return response(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", exception.getMessage());
  }

  @ExceptionHandler({FxUnavailableException.class, FxSystemWalletNotFoundException.class})
  public ResponseEntity<ApiError> fxUnavailable(RuntimeException exception) {
    return response(HttpStatus.SERVICE_UNAVAILABLE, "FX_UNAVAILABLE", exception.getMessage());
  }

  private static ResponseEntity<ApiError> response(HttpStatus status, String code, String message) {
    ApiError error = ApiErrorFactory.create(code, message, Map.of());
    return ResponseEntity.status(status).body(error);
  }
}
