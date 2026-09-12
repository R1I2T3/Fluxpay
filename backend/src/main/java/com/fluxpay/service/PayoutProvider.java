package com.fluxpay.service;

import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;

public interface PayoutProvider {
  String code();

  PayoutResult submit(PayoutCmd cmd);
}
