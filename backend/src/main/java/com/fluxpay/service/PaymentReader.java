package com.fluxpay.service;

public interface PaymentReader {
  PaymentSnapshot get(String paymentId);
}
