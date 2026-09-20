package com.fluxpay.web.advice;

import com.fluxpay.common.api.ApiError;
import com.fluxpay.common.web.ApiErrorFactory;
import com.fluxpay.controller.AdminKycController;
import com.fluxpay.controller.AuthController;
import com.fluxpay.controller.KycController;
import com.fluxpay.exception.AuthException;
import com.fluxpay.exception.KycException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Error mapping for authentication and KYC endpoints. */
@RestControllerAdvice(
    assignableTypes = {
      AuthController.class,
      KycController.class,
      com.fluxpay.controller.KycDocumentController.class,
      AdminKycController.class,
      com.fluxpay.controller.UserController.class
    })
public class AuthKycApiExceptionHandler {
  @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
  public ResponseEntity<ApiError> uploadTooLarge() {
    return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
        .body(error("VALIDATION", "Choose up to four documents, no larger than 5 MB each."));
  }

  @ExceptionHandler(AuthException.class)
  public ResponseEntity<ApiError> handleAuth(AuthException exception) {
    HttpStatus status =
        switch (exception.getCode()) {
          case AuthException.INVALID_CREDENTIALS -> HttpStatus.UNAUTHORIZED;
          case AuthException.EMAIL_EXISTS -> HttpStatus.CONFLICT;
          default -> HttpStatus.BAD_REQUEST;
        };
    return ResponseEntity.status(status).body(error(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(KycException.class)
  public ResponseEntity<ApiError> handleKyc(KycException exception) {
    HttpStatus status =
        switch (exception.getCode()) {
          case KycException.KYC_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case KycException.KYC_STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
          case KycException.REJECT_REASON_REQUIRED, KycException.VALIDATION ->
              HttpStatus.BAD_REQUEST;
          default -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status).body(error(exception.getCode(), exception.getMessage()));
  }

  private ApiError error(String code, String message) {
    return ApiErrorFactory.create(code, message, Map.of());
  }
}
