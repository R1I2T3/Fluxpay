package com.fluxpay.common.api;

import java.time.Instant;
import java.util.Map;

public record ApiError(
    String correlationId,
    String code,
    String message,
    Map<String, String> fieldErrors,
    Instant ts) {}
