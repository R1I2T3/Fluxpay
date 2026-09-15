package com.fluxpay.service;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.dto.*;
import com.fluxpay.config.M3BusinessException;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;

/** Only the original default consumer uses the frozen three-argument contract. */
final class LegacyM3ComplianceAdapter implements M3PaymentCompliancePort {
  private final ComplianceAssessor assessor;
  LegacyM3ComplianceAdapter(ComplianceAssessor assessor) { this.assessor=assessor; }
  public M3PaymentAssessment assess(M3PaymentFacts facts) {
    try {
      var verdict=CompletableFuture.supplyAsync(() -> assessor.assess(facts.senderId(),facts.amount(),facts.sourceCurrency())).get(3,TimeUnit.SECONDS);
      return new M3PaymentAssessment(UUID.randomUUID(),UUID.randomUUID(),1,"legacy",verdict,facts);
    } catch(InterruptedException interrupted) {
      Thread.currentThread().interrupt(); throw unavailable();
    } catch(ExecutionException | TimeoutException failure) { throw unavailable(); }
  }
  private static M3BusinessException unavailable() { return new M3BusinessException(HttpStatus.SERVICE_UNAVAILABLE,"COMPLIANCE_UNAVAILABLE","Compliance assessment unavailable."); }
}
