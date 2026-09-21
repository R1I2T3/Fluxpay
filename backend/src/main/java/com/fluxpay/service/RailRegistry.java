package com.fluxpay.service;

import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.exception.BusinessException;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Immutable finite registry of code-shipped transfer rails, keyed by rail type. */
@Service
public class RailRegistry {
  private final Map<RailType, TransferRail> rails;

  public RailRegistry(List<TransferRail> rails) {
    Objects.requireNonNull(rails, "rails must not be null");
    Map<RailType, TransferRail> index = new EnumMap<>(RailType.class);
    for (TransferRail rail : rails) {
      Objects.requireNonNull(rail, "rail must not be null");
      RailType type = Objects.requireNonNull(rail.type(), "rail type must not be null");
      if (index.putIfAbsent(type, rail) != null) {
        throw new IllegalStateException("Duplicate transfer rail for " + type);
      }
    }
    this.rails = Collections.unmodifiableMap(index);
  }

  public TransferRail require(RailType type) {
    TransferRail rail = rails.get(Objects.requireNonNull(type, "type must not be null"));
    if (rail == null) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "TRANSFER_RAIL_UNAVAILABLE",
          "No transfer rail is installed for " + type + ".");
    }
    return rail;
  }

  public TransferRail requireCompatible(RailType type, DestinationType destination) {
    TransferRail rail = require(type);
    Objects.requireNonNull(destination, "destination must not be null");
    if (!rail.supportedDestinations().contains(destination)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_TRANSFER_ROUTE",
          "Rail " + type + " does not support " + destination + ".");
    }
    return rail;
  }

  public Collection<TransferRail> all() {
    return rails.values();
  }
}
