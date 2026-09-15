package com.fluxpay.config;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.controller.M5ComplianceController;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Error mapping is restricted to the M5 risk controller, preserving other members' API handlers. */
@Profile("m5-risk")
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {M5ComplianceController.class,
    com.fluxpay.controller.M5PolicyController.class, com.fluxpay.controller.M5CopilotController.class})
public class M5RiskApiExceptionHandler {
  @ExceptionHandler(M5ApiException.class)
  public ResponseEntity<ApiError> domain(M5ApiException error) {
    return response(error.status(), error.code(), error.getMessage(), error.fieldErrors());
  }

  @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
      MissingServletRequestParameterException.class})
  public ResponseEntity<ApiError> malformed(Exception ignored) {
    return response(400, "VALIDATION", "Malformed request body, identifier, or query parameter", Map.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> validation(MethodArgumentNotValidException error) {
    var fields = new LinkedHashMap<String, String>();
    error.getBindingResult().getFieldErrors().forEach(field ->
        fields.put(field.getField(), String.valueOf(field.getDefaultMessage())));
    return response(400, "VALIDATION", "Request validation failed", fields);
  }

  @ExceptionHandler(RuntimeException.class)
  public ResponseEntity<ApiError> unexpected(RuntimeException ignored) {
    // Do not turn an unexpected persistence/implementation failure into an approval or a conflict.
    return response(500, "INTERNAL_ERROR", "Unable to complete the compliance request", Map.of());
  }

  private ResponseEntity<ApiError> response(int status, String code, String message,
      Map<String, String> fields) {
    String correlation = MDC.get("correlationId");
    if (correlation == null) correlation = UUID.randomUUID().toString();
    return ResponseEntity.status(status)
        .body(new ApiError(correlation, code, message, fields, Instant.now()));
  }
}
