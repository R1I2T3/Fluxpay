package com.fluxpay.repository;

import com.fluxpay.beans.M3OutboxDelivery;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface M3OutboxDeliveryRepository extends JpaRepository<M3OutboxDelivery, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select d from M3OutboxDelivery d where d.state='PENDING' and d.nextAttemptAt <= :now"
          + " and (d.leaseExpiresAt is null or d.leaseExpiresAt <= :now) order by d.nextAttemptAt asc")
  List<M3OutboxDelivery> claimEligible(@Param("now") Instant now);

  List<M3OutboxDelivery> findByPaymentIdOrderByAggregateSequenceAsc(UUID paymentId);
}
