package com.fluxpay.repository;

import com.fluxpay.beans.PaymentEvent;
import java.util.List;

public interface PaymentEventStore {

  boolean appendIfAbsent(PaymentEvent event);

  List<PaymentEvent> timeline(String paymentId);

  boolean contains(String paymentId, String eventType);
}
