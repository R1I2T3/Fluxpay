package com.fluxpay.common.contracts;

import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import java.util.Set;

/**
 * Trusted, code-shipped transfer execution behavior. Administrators configure providers and routes
 * that select a rail type; they never install executable rail code.
 */
public interface TransferRail {
  RailType type();

  Set<DestinationType> supportedDestinations();

  /**
   * Deliver under the persisted attempt's stable key. Repeating an identical command must replay
   * the same result. Return FAILED only when delivery was definitively rejected, and UNCERTAIN for
   * timeout or ambiguous delivery. An uncertain result must never be retried as new money.
   */
  TransferRailResult execute(TransferRailCommand command);
}
