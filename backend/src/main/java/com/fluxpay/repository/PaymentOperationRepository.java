package com.fluxpay.repository;

import com.fluxpay.beans.PaymentOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, UUID> {
  Optional<PaymentOperation> findByUserIdAndOperationTypeAndClientKey(
      UUID userId, String operationType, String clientKey);
}
