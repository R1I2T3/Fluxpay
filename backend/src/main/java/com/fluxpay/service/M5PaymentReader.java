package com.fluxpay.service;

import com.fluxpay.dto.M5PaymentSnapshot;
import java.util.UUID;

public interface M5PaymentReader {
  M5PaymentSnapshot readForAssessment(UUID paymentId);
  UUID ownerOf(UUID paymentId);
}
