package com.fluxpay.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class EventEnvelopeCodec {

  private final ObjectMapper objectMapper;

  public EventEnvelopeCodec(ObjectMapper objectMapper) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  public String write(PaymentEventEnvelope envelope) {
    try {
      return objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("event envelope cannot be serialized", e);
    }
  }

  public PaymentEventEnvelope read(String json) {
    final PaymentEventEnvelope envelope;
    try {
      envelope = objectMapper.readValue(json, PaymentEventEnvelope.class);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("event envelope is invalid", e);
    }
    PaymentEventEnvelope.validate(envelope);
    return envelope;
  }
}
