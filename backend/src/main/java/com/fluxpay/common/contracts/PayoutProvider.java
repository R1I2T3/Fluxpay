package com.fluxpay.common.contracts;

import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;

public interface PayoutProvider {
  String code();

  /**
   * Deliver under the persisted attempt's stable key. Repeating an identical command must replay
   * the same result. Return FAILED only when the provider definitively rejected delivery, and
   * UNCERTAIN for timeout or ambiguous delivery. Diagnostic codes are opaque and do not classify
   * certainty. An uncertain result must never be retried as new money.
   */
  PayoutResult submit(PayoutCmd cmd);
}
