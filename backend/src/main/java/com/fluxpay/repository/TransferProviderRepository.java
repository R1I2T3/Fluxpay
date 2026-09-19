package com.fluxpay.repository;

import com.fluxpay.beans.TransferProvider;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferProviderRepository extends JpaRepository<TransferProvider, UUID> {
  Optional<TransferProvider> findByProviderCode(String providerCode);

  List<TransferProvider> findAllByOrderByProviderCodeAsc();
}
