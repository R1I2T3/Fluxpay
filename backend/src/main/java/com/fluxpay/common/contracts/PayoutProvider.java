package com.fluxpay.common.contracts;

import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;

public interface PayoutProvider {
  String code();

  /**
   * Deliver under the persisted attempt's stable key. Repeating an identical command must replay
   * the same result. A final failure means the provider definitively rejected delivery; timeout or
   * ambiguous delivery must remain pending reconciliation and must never be retried as new money.
   */
  PayoutResult submit(PayoutCmd cmd);
}
