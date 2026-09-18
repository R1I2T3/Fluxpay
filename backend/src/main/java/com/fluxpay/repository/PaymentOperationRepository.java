package com.fluxpay.repository;

import com.fluxpay.beans.PaymentOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, UUID> {
  Optional<PaymentOperation> findByUserIdAndClientKey(UUID userId, String clientKey);

  java.util.List<PaymentOperation> findByPaymentIdAndStatus(UUID paymentId, String status);

  long countByPaymentIdAndOperationType(UUID paymentId, String operationType);
}
