package com.fluxpay.repository;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutAttemptRepository extends JpaRepository<PayoutAttempt, UUID> {

  Optional<PayoutAttempt> findFirstByPaymentIdOrderByAttemptNumberDesc(String paymentId);

  List<PayoutAttempt> findByRouteId(UUID routeId);

  @Query("select count(a) from PayoutAttempt a where a.routeId = :routeId and a.status = :status")
  long countByRouteIdAndStatus(
      @Param("routeId") UUID routeId, @Param("status") PayoutAttemptStatus status);

  @Query("select count(a) from PayoutAttempt a where a.routeId = :routeId")
  long countByRouteId(@Param("routeId") UUID routeId);
}
