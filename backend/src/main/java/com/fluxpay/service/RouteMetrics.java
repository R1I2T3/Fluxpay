package com.fluxpay.service;

import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.repository.PayoutAttemptRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Projects terminal-attempt success counts per route for the route list responses.
 *
 * <p>Counts only terminal (COMPLETED) attempts as successes; every stored attempt counts toward the
 * total. Uses count queries to avoid loading all attempts per route (N+1).
 */
@Service
public class RouteMetrics {

  private final PayoutAttemptRepository attempts;

  public RouteMetrics(PayoutAttemptRepository attempts) {
    this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
  }

  public RouteMetric byRoute(UUID routeId) {
    long total = attempts.countByRouteId(routeId);
    long success = attempts.countByRouteIdAndStatus(routeId, PayoutAttemptStatus.COMPLETED);
    return new RouteMetric(routeId, success, total);
  }

  public record RouteMetric(UUID routeId, long successCount, long totalCount) {}
}
