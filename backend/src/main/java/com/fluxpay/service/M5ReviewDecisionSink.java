package com.fluxpay.service;

import com.fluxpay.dto.M5DeliveryAck;
import com.fluxpay.dto.M5ReviewCommand;

public interface M5ReviewDecisionSink {
  M5DeliveryAck deliver(M5ReviewCommand command);
}
