package com.fluxpay.dto;

import java.util.UUID;

/** Explicit outcome of an administrator delete request: physical removal or archival. */
public record DeletionResult(Disposition disposition, UUID id) {
  public enum Disposition {
    DELETED,
    ARCHIVED
  }
}
