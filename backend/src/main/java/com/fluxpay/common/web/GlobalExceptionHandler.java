package com.fluxpay.common.web;
import com.fluxpay.common.api.ApiError;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;
@RestControllerAdvice
public class GlobalExceptionHandler {
  private String cid() { String v = MDC.get("correlationId"); return v == null ? "none" : v; }
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
    Map<String,String> fields = new HashMap<>();
    e.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), String.valueOf(f.getDefaultMessage())));
    return ResponseEntity.badRequest().body(new ApiError(cid(), "VALIDATION", "validation failed", fields, Instant.now()));
  }
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<ApiError> notFound(NoSuchElementException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiError(cid(), "NOT_FOUND", e.getMessage(), Map.of(), Instant.now()));
  }
  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiError> conflict(IllegalStateException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(cid(), "CONFLICT", e.getMessage(), Map.of(), Instant.now()));
  }
  @ExceptionHandler(SecurityException.class)
  public ResponseEntity<ApiError> auth(SecurityException e) {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(cid(), "AUTH", e.getMessage(), Map.of(), Instant.now()));
  }
}
