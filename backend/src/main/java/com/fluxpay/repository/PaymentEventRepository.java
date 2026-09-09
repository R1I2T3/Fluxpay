package com.fluxpay.repository;

import com.fluxpay.beans.PaymentEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, String> {

  List<PaymentEvent> findByPaymentIdOrderByOccurredAtAscEventIdAsc(String paymentId);

  boolean existsByPaymentIdAndEventType(String paymentId, String eventType);

  long countByPaymentId(String paymentId);
}
