package com.fluxpay.common.contracts;

import com.fluxpay.service.PaymentSnapshot;

public interface PaymentReader {
  PaymentSnapshot get(String paymentId);
}
