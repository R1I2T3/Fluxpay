package com.fluxpay.config;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.controller.AdminKycController;
import com.fluxpay.controller.KycController;
import com.fluxpay.service.M1KycException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps KYC persistence conflicts (e.g. unique-constraint races) to KYC_CONFLICT. */
@RestControllerAdvice(assignableTypes = {KycController.class, AdminKycController.class})
public class M1KycDataIntegrityHandler {
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ApiError> handleKycConflict(DataIntegrityViolationException exception) {
    String correlationId = MDC.get("correlationId");
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            new ApiError(
                correlationId == null ? "none" : correlationId,
                M1KycException.KYC_CONFLICT,
                "KYC data conflict",
                Map.of(),
                Instant.now()));
  }
}
