package com.fluxpay.config;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.controller.PaymentController;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Maps typed M5 preparation/disposition failures without modifying M3's existing advice. */
@Profile("m5-m3-integration") @RestControllerAdvice(assignableTypes=PaymentController.class)
public class M5IntegrationApiExceptionHandler {
  @ExceptionHandler(M5ApiException.class) public ResponseEntity<ApiError> m5(M5ApiException error) {
    return ResponseEntity.status(error.status()).body(new ApiError(MDC.get("correlationId"),
        error.code(),error.getMessage(),error.fieldErrors(),Instant.now()));
  }
}
