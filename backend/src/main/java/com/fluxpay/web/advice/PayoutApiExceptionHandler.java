package com.fluxpay.web.advice;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.common.web.ApiErrorFactory;
import com.fluxpay.exception.EventPublishException;
import com.fluxpay.exception.ForbiddenException;
import com.fluxpay.exception.QuoteExpiredException;
import com.fluxpay.exception.QuoteMismatchException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Payout failure mappings to the frozen {@link ApiError} record.
 *
 * <p>Only payout exceptions are mapped here; {@code NOT_FOUND}/{@code CONFLICT} for {@link
 * java.util.NoSuchElementException}/{@link IllegalStateException} stay with the frozen {@code
 * GlobalExceptionHandler}, which this advice never duplicates.
 */
@RestControllerAdvice(
    assignableTypes = {
      com.fluxpay.controller.PayoutController.class,
      com.fluxpay.controller.RouteController.class,
      com.fluxpay.controller.RouteAdminController.class,
      com.fluxpay.controller.TimelineController.class
    })
public class PayoutApiExceptionHandler {

  @ExceptionHandler(ForbiddenException.class)
  public ResponseEntity<ApiError> forbidden(ForbiddenException e) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiErrorFactory.create("FORBIDDEN", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(QuoteExpiredException.class)
  public ResponseEntity<ApiError> quoteExpired(QuoteExpiredException e) {
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
        .body(ApiErrorFactory.create("QUOTE_EXPIRED", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(QuoteMismatchException.class)
  public ResponseEntity<ApiError> quoteMismatch(QuoteMismatchException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorFactory.create("QUOTE_MISMATCH", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  public ResponseEntity<ApiError> optimisticLock(ObjectOptimisticLockingFailureException e) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ApiErrorFactory.create("CONFLICT", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
    return ResponseEntity.badRequest()
        .body(ApiErrorFactory.create("BAD_REQUEST", e.getMessage(), Map.of()));
  }

  @ExceptionHandler(EventPublishException.class)
  public ResponseEntity<ApiError> eventPublish(EventPublishException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiErrorFactory.create("EVENT_PUBLISH_FAILED", e.getMessage(), Map.of()));
  }
}
