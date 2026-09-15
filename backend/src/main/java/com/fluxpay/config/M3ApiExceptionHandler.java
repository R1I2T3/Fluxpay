package com.fluxpay.config;

import com.fluxpay.common.api.ApiError;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(
    assignableTypes = {
      com.fluxpay.controller.RecipientController.class,
      com.fluxpay.controller.PaymentController.class
    })
public class M3ApiExceptionHandler {
  @ExceptionHandler(M3BusinessException.class)
  ResponseEntity<ApiError> business(M3BusinessException e) {
    return ResponseEntity.status(e.status())
        .body(
            new ApiError(
                MDC.get("correlationId"), e.code(), e.getMessage(), Map.of(), Instant.now()));
  }
}
