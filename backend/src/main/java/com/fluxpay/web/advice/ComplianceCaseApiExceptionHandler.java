package com.fluxpay.web.advice;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.common.web.ApiErrorFactory;
import com.fluxpay.controller.ComplianceCaseController;
import com.fluxpay.exception.BusinessException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Preserves domain errors from a compliance decision without falling through to Spring's /error
 * path.
 */
@RestControllerAdvice(assignableTypes = ComplianceCaseController.class)
public class ComplianceCaseApiExceptionHandler {

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiError> business(BusinessException exception) {
    return ResponseEntity.status(exception.status())
        .body(ApiErrorFactory.create(exception.code(), exception.getMessage(), Map.of()));
  }
}
