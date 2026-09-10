package com.fluxpay.service;

/** Infrastructure failure publishing to Kafka. Mapped to 503, never 4xx. */
public class EventPublishException extends RuntimeException {
  public EventPublishException(String message, Throwable cause) {
    super(message, cause);
  }
}
