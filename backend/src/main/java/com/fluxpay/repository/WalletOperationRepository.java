package com.fluxpay.repository;

import com.fluxpay.beans.WalletOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface WalletOperationRepository extends Repository<WalletOperation, UUID> {
  WalletOperation saveAndFlush(WalletOperation operation);

  Optional<WalletOperation> findByUserIdAndOperationTypeAndClientKey(
      UUID userId, String operationType, String clientKey);
}
