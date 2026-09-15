package com.fluxpay.common.contracts;

public interface TransportPort {
  void send(String topic, String payload, String key) throws Exception;
}
