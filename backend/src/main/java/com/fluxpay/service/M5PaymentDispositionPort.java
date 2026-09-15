package com.fluxpay.service;
import com.fluxpay.dto.M3PaymentAssessment;
import java.util.UUID;
/** Implementations must join M3's transaction and retain head/case locks until its commit. */
public interface M5PaymentDispositionPort {
  void lockAndValidate(M3PaymentAssessment assessment);
  void record(M3PaymentAssessment assessment, String disposition, UUID reviewReference);
}
