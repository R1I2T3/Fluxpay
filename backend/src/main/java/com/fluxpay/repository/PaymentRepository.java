package com.fluxpay.repository;

import com.fluxpay.beans.Payment;
import com.fluxpay.domain.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
  Optional<Payment> findByIdAndSenderId(UUID id, UUID senderId);

  Page<Payment> findBySenderIdOrderByCreatedAtDescIdDesc(UUID senderId, Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.id=:id and p.senderId=:senderId")
  Optional<Payment> lockOwned(@Param("id") UUID id, @Param("senderId") UUID senderId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.id=:id")
  Optional<Payment> lockById(@Param("id") UUID id);

  boolean existsBySenderIdAndRecipientIdAndIdNotAndStatusIn(
      UUID senderId, UUID recipientId, UUID paymentId, Collection<PaymentStatus> statuses);

  default boolean existsPriorSubmittedPaymentForRecipient(
      UUID senderId, UUID recipientId, UUID paymentId) {
    return existsBySenderIdAndRecipientIdAndIdNotAndStatusIn(
        senderId,
        recipientId,
        paymentId,
        List.of(
            PaymentStatus.UNDER_REVIEW,
            PaymentStatus.PROCESSING,
            PaymentStatus.COMPLETED,
            PaymentStatus.FAILED,
            PaymentStatus.REFUNDED,
            PaymentStatus.REJECTED));
  }
}
