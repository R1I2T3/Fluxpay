package com.fluxpay.common.web;

import com.fluxpay.common.api.ApiError;
import java.time.Instant;
import java.util.Map;
import org.slf4j.MDC;

public final class ApiErrorFactory {
  private ApiErrorFactory() {}

  public static ApiError create(String code, String message, Map<String, String> fields) {
    String correlationId = MDC.get("correlationId");
    return create(code, message, fields, correlationId == null ? "none" : correlationId);
  }

  /** Allows callers with an existing nullable correlation-ID contract to retain it. */
  public static ApiError create(
      String code, String message, Map<String, String> fields, String correlationId) {
    return new ApiError(correlationId, code, message, fields, Instant.now());
  }
}
