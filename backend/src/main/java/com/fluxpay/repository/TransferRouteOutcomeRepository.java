package com.fluxpay.repository;

import com.fluxpay.beans.TransferRouteOutcome;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferRouteOutcomeRepository extends JpaRepository<TransferRouteOutcome, UUID> {
  boolean existsByRouteId(UUID routeId);

  Optional<TransferRouteOutcome> findByExecutionReference(String executionReference);

  /** One grouped row per route with terminal COMPLETED/FAILED counts for the given routes. */
  @Query(
      """
      select o.routeId as routeId,
        sum(case when o.outcome = com.fluxpay.domain.RouteOutcome.COMPLETED then 1 else 0 end)
          as completed,
        sum(case when o.outcome = com.fluxpay.domain.RouteOutcome.FAILED then 1 else 0 end)
          as failed
      from TransferRouteOutcome o
      where o.routeId in :routeIds
      group by o.routeId
      """)
  List<RouteOutcomeCounts> countByRouteIds(@Param("routeIds") Collection<UUID> routeIds);

  interface RouteOutcomeCounts {
    UUID getRouteId();

    long getCompleted();

    long getFailed();
  }
}
