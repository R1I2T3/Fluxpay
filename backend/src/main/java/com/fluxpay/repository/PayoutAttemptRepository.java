package com.fluxpay.repository;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutAttemptRepository extends JpaRepository<PayoutAttempt, UUID> {

  // The first attempt is a stable lock target even when a recovery creates a newer attempt.
  // Held until transaction completion across every recovery action and application instance.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from PayoutAttempt a where a.paymentId = :paymentId and a.attemptNumber = 1")
  Optional<PayoutAttempt> lockPayment(@Param("paymentId") String paymentId);

  Optional<PayoutAttempt> findFirstByPaymentIdOrderByAttemptNumberDesc(String paymentId);

  List<PayoutAttempt> findByRouteId(UUID routeId);

  @Query("select count(a) from PayoutAttempt a where a.routeId = :routeId and a.status = :status")
  long countByRouteIdAndStatus(
      @Param("routeId") UUID routeId, @Param("status") PayoutAttemptStatus status);

  @Query("select count(a) from PayoutAttempt a where a.routeId = :routeId")
  long countByRouteId(@Param("routeId") UUID routeId);
}
