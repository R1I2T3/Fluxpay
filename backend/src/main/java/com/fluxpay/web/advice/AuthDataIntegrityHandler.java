package com.fluxpay.web.advice;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.controller.AuthController;
import com.fluxpay.exception.AuthException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps concurrent-registration races to EMAIL_EXISTS. Scoped to auth only. */
@RestControllerAdvice(assignableTypes = {AuthController.class})
public class AuthDataIntegrityHandler {
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiError> handleRegistrationRace(
      DataIntegrityViolationException exception) {
    String correlationId = MDC.get("correlationId");
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ApiError(
                correlationId == null ? "none" : correlationId,
                AuthException.EMAIL_EXISTS,
                "email is already registered",
                Map.of(),
                Instant.now()));
  }
}
