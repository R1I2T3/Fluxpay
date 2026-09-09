package com.fluxpay.service;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.PayoutAttemptStatus;
import com.fluxpay.repository.PayoutAttemptRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Projects terminal-attempt success counts per route for the route list responses.
 *
 * <p>Counts only terminal (COMPLETED) attempts as successes; every stored attempt counts toward the
 * total.
 */
@Service
public class RouteMetrics {

  private final PayoutAttemptRepository attempts;

  public RouteMetrics(PayoutAttemptRepository attempts) {
    this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
  }

  public RouteMetric byRoute(String routeId) {
    List<PayoutAttempt> rows = attempts.findByRouteId(routeId);
    long success =
        rows.stream().filter(attempt -> attempt.status() == PayoutAttemptStatus.COMPLETED).count();
    return new RouteMetric(routeId, success, rows.size());
  }

  public record RouteMetric(String routeId, long successCount, long totalCount) {}
}
