package com.fluxpay.service;
import com.fluxpay.beans.*;
import com.fluxpay.dto.M3PaymentAssessment;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.repository.M5ScreeningStore;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

public final class M5JoiningDispositionService implements M5PaymentDispositionPort {
  private final M5ScreeningStore store; private final TransactionTemplate tx;
  public M5JoiningDispositionService(M5ScreeningStore store,PlatformTransactionManager manager) {
    this.store=store; tx=new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
  }
  public void lockAndValidate(M3PaymentAssessment a) { tx.executeWithoutResult(s -> locked(a)); }
  private M5ScreeningCase locked(M3PaymentAssessment a) {
    var head=store.head(a.facts().paymentId(),true).orElseThrow(M5JoiningDispositionService::stale);
    var c=store.byCase(a.caseId(),true).orElseThrow(M5JoiningDispositionService::stale);
    var saved=c.assessment();
    if(!Objects.equals(head.latestCaseId(),a.caseId()) || head.latestSequence()!=a.sequence()
        || !saved.paymentId().equals(a.facts().paymentId()) || !saved.assessmentId().equals(a.assessmentId())
        || !saved.expectedPaymentFingerprint().equals(a.paymentFingerprint())
        || !saved.screeningVerdict().equals(a.verdict().name()) || c.paymentDisposition()!=null) throw stale();
    return c;
  }
  public void record(M3PaymentAssessment a,String disposition,UUID reference) {
    tx.executeWithoutResult(s -> {
      if(!Set.of("PROCEED","BLOCKED","REVIEW_REQUIRED").contains(disposition)
          || ("REVIEW_REQUIRED".equals(disposition)!=(reference!=null))) throw stale();
      var c=locked(a);
      if(("BLOCKED".equals(disposition) && !"BLOCK".equals(c.assessment().screeningVerdict()))
          || ("REVIEW_REQUIRED".equals(disposition) && !"REVIEW".equals(c.assessment().screeningVerdict()))
          || ("PROCEED".equals(disposition) && "BLOCK".equals(c.assessment().screeningVerdict()))) throw stale();
      store.updateCase(new M5ScreeningCase(c.assessment(),c.snapshot(),c.requestFingerprint(),c.status(),c.verdict(),c.suggestedAction(),disposition,reference,c.decidedBy(),c.decidedAt(),c.decisionReason(),c.version()+1));
    });
  }
  private static M5ApiException stale() { return new M5ApiException(409,"STALE_ASSESSMENT","Assessment is no longer the latest unconsumed payment assessment"); }
}
