package com.fluxpay.service;

import com.fluxpay.common.contracts.PaymentEligibilityGate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DbPaymentEligibilityGate implements PaymentEligibilityGate {
  private final SelectedQuoteService selectedQuotes;

  public DbPaymentEligibilityGate(SelectedQuoteService selectedQuotes) {
    this.selectedQuotes = selectedQuotes;
  }

  @Override
  @Transactional(readOnly = true)
  public void assertActiveQuote(PaymentSnapshot payment, String requestedRouteCode) {
    selectedQuotes.require(payment, requestedRouteCode);
  }
}
