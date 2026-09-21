package com.fluxpay.service;

import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.exception.BusinessException;

/**
 * Shared rail/destination compatibility check for catalogue administration.
 *
 * <p>Active routes require an installed compatible rail. Inactive routes may retain configuration
 * for an uninstalled rail, while a definitive incompatibility reported by an installed rail is
 * still rejected. Eligibility and execution repeat the strict check as defense in depth.
 */
final class RoutingCompatibility {
  private RoutingCompatibility() {}

  static void requireCompatible(
      RailRegistry rails, RailType railType, DestinationType destinationType, boolean active) {
    if (active) {
      rails.requireCompatible(railType, destinationType);
      return;
    }
    requireCompatibleIfInstalled(rails, railType, destinationType);
  }

  static void requireCompatibleIfInstalled(
      RailRegistry rails, RailType railType, DestinationType destinationType) {
    try {
      rails.requireCompatible(railType, destinationType);
    } catch (BusinessException e) {
      if ("TRANSFER_RAIL_UNAVAILABLE".equals(e.code())) {
        return;
      }
      throw e;
    }
  }
}
