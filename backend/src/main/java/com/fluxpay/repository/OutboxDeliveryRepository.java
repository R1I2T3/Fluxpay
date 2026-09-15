package com.fluxpay.repository;

import com.fluxpay.beans.OutboxDelivery;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OutboxDeliveryRepository extends JpaRepository<OutboxDelivery, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "select d from OutboxDelivery d where"
          + " ((d.state='PENDING' and d.nextAttemptAt <= :now)"
          + " or (d.state='SENDING' and d.leaseExpiresAt <= :now))"
          + " and not exists (select earlier.eventId from OutboxDelivery earlier"
          + " where earlier.paymentId = d.paymentId"
          + " and earlier.aggregateSequence < d.aggregateSequence"
          + " and earlier.state <> 'SENT')"
          + " order by d.nextAttemptAt asc, d.aggregateSequence asc")
  List<OutboxDelivery> claimEligible(@Param("now") Instant now, Pageable pageable);

  List<OutboxDelivery> findByPaymentIdOrderByAggregateSequenceAsc(UUID paymentId);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update OutboxDelivery d set d.state='SENT', d.sentAt=:sentAt"
          + " where d.eventId=:eventId and d.state='SENDING' and d.claimToken=:claimToken")
  int markSentIfClaimed(
      @Param("eventId") UUID eventId,
      @Param("claimToken") String claimToken,
      @Param("sentAt") Instant sentAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update OutboxDelivery d set d.state='PENDING', d.attemptCount=d.attemptCount+1,"
          + " d.nextAttemptAt=:nextAttemptAt, d.claimToken=null, d.leaseExpiresAt=null,"
          + " d.lastError=:lastError"
          + " where d.eventId=:eventId and d.state='SENDING' and d.claimToken=:claimToken")
  int scheduleRetryIfClaimed(
      @Param("eventId") UUID eventId,
      @Param("claimToken") String claimToken,
      @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("lastError") String lastError);
}
