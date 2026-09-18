package com.fluxpay.repository;

import com.fluxpay.beans.TransferRoute;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRouteRepository extends JpaRepository<TransferRoute, UUID> {

  Optional<TransferRoute> findByRouteCode(String routeCode);

  List<TransferRoute> findAllByOrderByRouteCodeAsc();

  List<TransferRoute> findByProviderIdOrderByRouteCodeAsc(UUID providerId);

  boolean existsByProviderId(UUID providerId);

  List<TransferRoute> findByActiveTrueOrderByRouteCodeAsc();
}
