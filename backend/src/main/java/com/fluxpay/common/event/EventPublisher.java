package com.fluxpay.common.event;
public interface EventPublisher { void publish(String topic, Object payload, String correlationId); }
