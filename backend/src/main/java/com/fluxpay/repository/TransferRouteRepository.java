package com.fluxpay.repository;

import com.fluxpay.beans.TransferRoute;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRouteRepository extends JpaRepository<TransferRoute, UUID> {

  Optional<TransferRoute> findByRouteCode(String routeCode);

  @EntityGraph(attributePaths = "provider")
  List<TransferRoute> findAllByOrderByRouteCodeAsc();

  @EntityGraph(attributePaths = "provider")
  @Override
  Optional<TransferRoute> findById(UUID id);

  List<TransferRoute> findByProviderIdOrderByRouteCodeAsc(UUID providerId);

  boolean existsByProviderId(UUID providerId);

  @EntityGraph(attributePaths = "provider")
  List<TransferRoute> findByActiveTrueOrderByRouteCodeAsc();
}
