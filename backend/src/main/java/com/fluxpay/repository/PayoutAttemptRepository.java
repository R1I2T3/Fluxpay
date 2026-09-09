package com.fluxpay.repository;

import com.fluxpay.beans.PayoutAttempt;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface PayoutAttemptRepository extends JpaRepository<PayoutAttempt, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PayoutAttempt> findFirstByPaymentIdOrderByAttemptNumberDesc(String paymentId);

  List<PayoutAttempt> findByRouteId(String routeId);
}
