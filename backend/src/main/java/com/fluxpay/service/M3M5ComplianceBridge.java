package com.fluxpay.service;
import com.fluxpay.dto.*;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.M5ApiException;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Fresh observations and sequence retries are M5-owned and independently committed. */
public final class M3M5ComplianceBridge implements M3PaymentCompliancePort {
  private final M5ComplianceService compliance;
  private final M5PaymentReader reader;
  private final TransactionTemplate tx;
  public M3M5ComplianceBridge(M5ComplianceService compliance,M5PaymentReader reader,PlatformTransactionManager manager) {
    this.compliance=compliance; this.reader=reader; tx=new TransactionTemplate(manager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(5);
  }
  public M3PaymentAssessment assess(M3PaymentFacts facts) {
    var snapshot=tx.execute(s -> reader.readForAssessment(facts.paymentId()));
    var observed=new M3PaymentFacts(snapshot.paymentId(),snapshot.senderId(),snapshot.walletId(),snapshot.recipientId(),
      snapshot.sourceAmount().stripTrailingZeros(),snapshot.sourceCurrency(),snapshot.payoutCurrency(),snapshot.purpose(),snapshot.recipientSnapshot(),snapshot.recipientVersion());
    if(!facts.equals(observed)) throw new M5ApiException(409,"STALE_ASSESSMENT","Payment facts changed before screening");
    String fingerprint=M5Fingerprints.payment(snapshot);
    for(int attempt=0;attempt<5;attempt++) {
      try {
        var a=compliance.assess(new M5AssessmentRequest(UUID.randomUUID(),compliance.latestSequence(facts.paymentId())+1,facts.paymentId(),fingerprint));
        return new M3PaymentAssessment(a.assessmentId(),a.caseId(),a.assessmentSequence(),a.expectedPaymentFingerprint(),ScreeningVerdict.valueOf(a.screeningVerdict()),facts);
      } catch(M5ApiException conflict) {
        if(!"STALE_ASSESSMENT".equals(conflict.code()) || attempt==4) throw conflict;
      }
    }
    throw new IllegalStateException("Unreachable assessment allocation");
  }
}
