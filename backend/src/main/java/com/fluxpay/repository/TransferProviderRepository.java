package com.fluxpay.repository;

import com.fluxpay.beans.TransferProvider;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferProviderRepository extends JpaRepository<TransferProvider, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select provider from TransferProvider provider where provider.id = :id")
  Optional<TransferProvider> findByIdForUpdate(@Param("id") UUID id);

  Optional<TransferProvider> findByProviderCode(String providerCode);

  List<TransferProvider> findAllByOrderByProviderCodeAsc();
}
