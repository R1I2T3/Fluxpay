package com.fluxpay.repository;

import com.fluxpay.beans.M3PaymentOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface M3PaymentOperationRepository extends JpaRepository<M3PaymentOperation, UUID> {
  Optional<M3PaymentOperation> findByUserIdAndOperationTypeAndClientKey(
      UUID userId, String operationType, String clientKey);
}
