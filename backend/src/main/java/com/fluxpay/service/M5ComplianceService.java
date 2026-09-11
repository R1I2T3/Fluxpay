package com.fluxpay.service;
import com.fluxpay.dto.*;
import com.fluxpay.repository.M5ScreeningStore;
import com.fluxpay.common.security.CurrentUser;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
public class M5ComplianceService implements M5AssessmentPort, M5CaseContextReader {
  public M5ComplianceService(M5ScreeningStore store,M5PaymentReader reader,M5ComplianceRulesEngine rules,
      PlatformTransactionManager transactions,Clock clock) {}
  public M5AssessmentResponse assess(M5AssessmentRequest request) { return assessOutcome(request).response(); }
  public M5AssessmentOutcome assessOutcome(M5AssessmentRequest request) { throw new UnsupportedOperationException(); }
  public M5CaseResponse recordDisposition(UUID assessmentId,String disposition,UUID reference) { throw new UnsupportedOperationException(); }
  public M5CaseResponse decide(UUID caseId,String action,String reason,CurrentUser actor) { throw new UnsupportedOperationException(); }
  public M5CaseResponse get(UUID caseId,CurrentUser actor) { throw new UnsupportedOperationException(); }
  public M5CaseResponse passport(UUID paymentId,CurrentUser actor) { throw new UnsupportedOperationException(); }
  public long latestSequence(UUID paymentId) { throw new UnsupportedOperationException(); }
  public M5CasePage list(String status,String risk,Boolean reviewable,int page,int size,CurrentUser actor) { throw new UnsupportedOperationException(); }
  public Map<String,Object> context(UUID paymentId,CurrentUser actor) { throw new UnsupportedOperationException(); }
}
