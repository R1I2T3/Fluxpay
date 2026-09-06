package com.fluxpay.common.api;

public record ApiResponse<T>(String correlationId, T data) {}
