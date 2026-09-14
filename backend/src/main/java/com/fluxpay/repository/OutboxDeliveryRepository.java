package com.fluxpay.repository;

import com.fluxpay.beans.OutboxDelivery;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxDeliveryRepository extends JpaRepository<OutboxDelivery, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select d from OutboxDelivery d where"
          + " (d.state='PENDING' and d.nextAttemptAt <= :now)"
          + " or (d.state='SENDING' and d.leaseExpiresAt <= :now)"
          + " order by d.nextAttemptAt asc, d.aggregateSequence asc")
  List<OutboxDelivery> claimEligible(@Param("now") Instant now);

  List<OutboxDelivery> findByPaymentIdOrderByAggregateSequenceAsc(UUID paymentId);

  @Query(
      "select count(d) > 0 from OutboxDelivery d where d.paymentId = :paymentId"
          + " and d.aggregateSequence < :sequence and d.state <> 'SENT'")
  boolean existsUnsentEarlier(@Param("paymentId") UUID paymentId, @Param("sequence") int sequence);
}
