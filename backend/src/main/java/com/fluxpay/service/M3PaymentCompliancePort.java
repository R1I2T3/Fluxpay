package com.fluxpay.service;
import com.fluxpay.dto.M3PaymentAssessment;
import com.fluxpay.dto.M3PaymentFacts;
public interface M3PaymentCompliancePort {
  M3PaymentAssessment assess(M3PaymentFacts facts);
}
