package com.fluxpay.dto;

import com.fluxpay.domain.DestinationType;
import java.util.Objects;
import java.util.UUID;

/** Immutable route identity frozen into a rail command at reservation time. */
public record TransferRouteSnapshot(
    UUID id, String code, DestinationType destinationType, UUID providerId) {
  public TransferRouteSnapshot {
    Objects.requireNonNull(id, "id must not be null");
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    Objects.requireNonNull(destinationType, "destinationType must not be null");
    Objects.requireNonNull(providerId, "providerId must not be null");
  }
}
