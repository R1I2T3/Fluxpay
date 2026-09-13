package com.fluxpay.web.advice;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.common.web.ApiErrorFactory;
import com.fluxpay.exception.BusinessException;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(
    assignableTypes = {
      com.fluxpay.controller.RecipientController.class,
      com.fluxpay.controller.PaymentController.class
    })
public class PaymentApiExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  ResponseEntity<ApiError> business(BusinessException e) {
    return ResponseEntity.status(e.status())
        .body(ApiErrorFactory.create(e.code(), e.getMessage(), Map.of(), MDC.get("correlationId")));
  }
}
