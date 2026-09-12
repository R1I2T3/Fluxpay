package com.fluxpay.service;

public interface M3TransportPort {
  void send(String topic, String payload, String key) throws Exception;
}
