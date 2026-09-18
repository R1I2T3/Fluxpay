package com.fluxpay.web.advice;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.common.web.ApiErrorFactory;
import com.fluxpay.controller.AdminReportController;
import com.fluxpay.controller.AdminTicketController;
import com.fluxpay.controller.TicketController;
import com.fluxpay.exception.BusinessException;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    assignableTypes = {
      TicketController.class,
      AdminTicketController.class,
      AdminReportController.class
    })
public class TicketApiExceptionHandler {
  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiError> business(BusinessException exception) {
    return ResponseEntity.status(exception.status())
        .body(
            ApiErrorFactory.create(
                exception.code(), exception.getMessage(), Map.of(), MDC.get("correlationId")));
  }
}
