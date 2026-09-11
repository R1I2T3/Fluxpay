package com.fluxpay.config;

import java.util.Map;

/** M5 domain failure mapped by the route-scoped exception handler. */
public final class M5ApiException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, String> fieldErrors;

    public M5ApiException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public M5ApiException(int status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, String> fieldErrors() { return fieldErrors; }
}
