package com.fluxpay.config;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.service.EventPublishException;
import com.fluxpay.service.ForbiddenException;
import com.fluxpay.service.QuoteExpiredException;
import com.fluxpay.service.QuoteMismatchException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * M4-specific failure mappings to the frozen {@link ApiError} record.
 *
 * <p>Only M4 exceptions are mapped here; {@code NOT_FOUND}/{@code CONFLICT} for {@link
 * java.util.NoSuchElementException}/{@link IllegalStateException} stay with the frozen {@code
 * GlobalExceptionHandler}, which this advice never duplicates.
 */
@RestControllerAdvice
public class M4ApiExceptionHandler {

  private String cid() {
    String value = MDC.get("correlationId");
    return value == null ? "none" : value;
  }

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<ApiError> forbidden(ForbiddenException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ApiError(cid(), "FORBIDDEN", e.getMessage(), Map.of(), Instant.now()));
  }

  @ExceptionHandler(QuoteExpiredException.class)
  public ResponseEntity<ApiError> quoteExpired(QuoteExpiredException e) {
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
        .body(new ApiError(cid(), "QUOTE_EXPIRED", e.getMessage(), Map.of(), Instant.now()));
  }

  @ExceptionHandler(QuoteMismatchException.class)
  public ResponseEntity<ApiError> quoteMismatch(QuoteMismatchException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiError(cid(), "QUOTE_MISMATCH", e.getMessage(), Map.of(), Instant.now()));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiError> optimisticLock(ObjectOptimisticLockingFailureException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ApiError(cid(), "CONFLICT", e.getMessage(), Map.of(), Instant.now()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(new ApiError(cid(), "BAD_REQUEST", e.getMessage(), Map.of(), Instant.now()));
  }

  @ExceptionHandler(EventPublishException.class)
  public ResponseEntity<ApiError> eventPublish(EventPublishException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ApiError(cid(), "EVENT_PUBLISH_FAILED", e.getMessage(), Map.of(), Instant.now()));
  }
}
