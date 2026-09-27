package com.fluxpay.dto;

import com.fluxpay.domain.RailType;
import java.util.Objects;
import java.util.UUID;

/** Immutable provider identity frozen into a rail command at reservation time. */
public record TransferProviderSnapshot(UUID id, String code, RailType railType) {
  public TransferProviderSnapshot {
    Objects.requireNonNull(id, "id must not be null");
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    Objects.requireNonNull(railType, "railType must not be null");
  }
}
