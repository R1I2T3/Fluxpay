package com.fluxpay.repository;

import com.fluxpay.beans.Payment;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {
  Optional<Payment> findByIdAndSenderId(UUID id, UUID senderId);

  Page<Payment> findBySenderIdAndFlowVersionOrderByCreatedAtDescIdDesc(
      UUID senderId, int flowVersion, Pageable page);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.id=:id and p.senderId=:senderId")
  Optional<Payment> lockOwned(@Param("id") UUID id, @Param("senderId") UUID senderId);
}
