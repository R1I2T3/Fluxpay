package com.fluxpay.config; import org.springframework.http.HttpStatus;
public class M3BusinessException extends RuntimeException { private final HttpStatus status; private final String code; public M3BusinessException(HttpStatus status,String code,String message){super(message);this.status=status;this.code=code;} public HttpStatus status(){return status;} public String code(){return code;} }
