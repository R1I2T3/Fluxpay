package com.fluxpay.service;

import com.fluxpay.beans.TransferRouteOutcome;
import com.fluxpay.domain.RouteOutcome;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent terminal outcome projection shared by internal and external execution flows. Replaying
 * an identical {@code (routeId, executionReference, outcome)} returns the existing row; reusing a
 * reference with different content is rejected as a conflict.
 */
@Service
public class RouteOutcomeRecorder {

  private final TransferRouteOutcomeRepository outcomes;
  private final Clock clock;

  public RouteOutcomeRecorder(TransferRouteOutcomeRepository outcomes, Clock clock) {
    this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Transactional
  public TransferRouteOutcome record(
      UUID routeId, String executionReference, RouteOutcome outcome) {
    Objects.requireNonNull(routeId, "routeId must not be null");
    Objects.requireNonNull(executionReference, "executionReference must not be null");
    Objects.requireNonNull(outcome, "outcome must not be null");
    return outcomes
        .findByExecutionReference(executionReference)
        .map(
            existing -> {
              if (existing.routeId().equals(routeId) && existing.outcome() == outcome) {
                return existing;
              }
              throw new BusinessException(
                  HttpStatus.CONFLICT,
                  "DUPLICATE_EXECUTION_REFERENCE",
                  "The execution reference was already recorded with different content.");
            })
        .orElseGet(
            () ->
                outcomes.saveAndFlush(
                    TransferRouteOutcome.record(
                        UUID.randomUUID(),
                        routeId,
                        executionReference,
                        outcome,
                        Instant.now(clock))));
  }
}
