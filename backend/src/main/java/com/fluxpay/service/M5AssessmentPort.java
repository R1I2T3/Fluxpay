package com.fluxpay.service;

import com.fluxpay.dto.M5AssessmentRequest;
import com.fluxpay.dto.M5AssessmentResponse;

public interface M5AssessmentPort {
  M5AssessmentResponse assess(M5AssessmentRequest request);
}
