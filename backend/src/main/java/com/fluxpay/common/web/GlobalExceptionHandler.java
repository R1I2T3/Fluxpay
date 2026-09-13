package com.fluxpay.common.web;

import com.fluxpay.common.api.ApiError;
import java.util.*;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiError> optimisticLock(
      ObjectOptimisticLockingFailureException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorFactory.create("CONFLICT", exception.getMessage(), Map.of()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
    Map<String, String> fields = new HashMap<>();
    e.getBindingResult()
        .getFieldErrors()
        .forEach(f -> fields.put(f.getField(), String.valueOf(f.getDefaultMessage())));
    return ResponseEntity.badRequest()
        .body(ApiErrorFactory.create("VALIDATION", "validation failed", fields));
  }

  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ApiError> notFound(NoSuchElementException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiErrorFactory.create("NOT_FOUND", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiError> conflict(IllegalStateException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorFactory.create("CONFLICT", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<ApiError> auth(SecurityException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
        .body(ApiErrorFactory.create("AUTH", e.getMessage(), Map.of()));
  }
}
