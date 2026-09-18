package com.fluxpay.repository;

import com.fluxpay.beans.TransferRouteOutcome;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRouteOutcomeRepository extends JpaRepository<TransferRouteOutcome, UUID> {
  boolean existsByRouteId(UUID routeId);
}
