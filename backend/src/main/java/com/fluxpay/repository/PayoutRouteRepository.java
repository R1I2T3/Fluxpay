package com.fluxpay.repository;

import com.fluxpay.beans.PayoutRoute;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutRouteRepository extends JpaRepository<PayoutRoute, String> {

  /** Lookup by business code; explicit query because the entity field is {@code routeCode}. */
  @Query("select r from PayoutRoute r where r.routeCode = :code")
  Optional<PayoutRoute> findByCode(@Param("code") String code);

  List<PayoutRoute> findAllByOrderByRouteCodeAsc();

  List<PayoutRoute> findByActiveTrueOrderByRouteCodeAsc();
}
