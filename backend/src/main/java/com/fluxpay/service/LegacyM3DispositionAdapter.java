package com.fluxpay.service;
import com.fluxpay.dto.M3PaymentAssessment;
import java.util.UUID;
/** The frozen legacy assessor owns no M5 assessment; integration must never select this adapter. */
final class LegacyM3DispositionAdapter implements M5PaymentDispositionPort {
  public void lockAndValidate(M3PaymentAssessment assessment) {}
  public void record(M3PaymentAssessment assessment,String disposition,UUID reference) {}
}
