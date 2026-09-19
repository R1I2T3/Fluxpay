package com.fluxpay.service;

import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.exception.BusinessException;

/**
 * Shared rail/destination compatibility check for catalogue administration.
 *
 * <p>A rail type that is not installed in this deployment cannot prove incompatibility: the
 * catalogue record stays valid and later eligibility/execution stages (which require an installed
 * compatible rail) keep it out of traffic. Only a definitive incompatibility reported by an
 * installed rail is rejected here.
 */
final class RoutingCompatibility {
  private RoutingCompatibility() {}

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
