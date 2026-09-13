package com.fluxpay.common.contracts;

import com.fluxpay.service.PaymentSnapshot;

public interface PaymentEligibilityGate {
  void assertActiveQuote(PaymentSnapshot payment, String requestedRouteCode);
}
