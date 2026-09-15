package com.fluxpay.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.*;
import com.fluxpay.dto.*;
import com.fluxpay.repository.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** No HTTP endpoint. Acknowledgments are returned only after the M3 transaction commits. */
public final class M3ReviewService implements M3ReviewDecisionPort {
  private final PaymentRepository payments; private final M3ReviewDecisionRepository decisions;
  private final ObjectMapper json; private final Clock clock; private final TransactionTemplate tx;
  public M3ReviewService(PaymentRepository payments,M3ReviewDecisionRepository decisions,ObjectMapper json,
      Clock clock,PlatformTransactionManager manager) {
    this.payments=payments; this.decisions=decisions; this.json=json; this.clock=clock;
    tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(5);
  }
  public M3ReviewAck accept(M3ReviewCommand input) {
    if(!valid(input)) return new M3ReviewAck(input==null?null:input.decisionId(),"CONFLICT");
    var c=new M3ReviewCommand(input.decisionId(),input.caseId(),input.assessmentId(),input.paymentId(),input.reviewReference(),input.paymentFingerprint(),input.decision(),input.reviewerId(),input.decidedAt(),normalizeReason(input.reason()));
    String normalized=encode(c);
    try { return tx.execute(s -> acceptLocked(c,normalized)); }
    catch(DataIntegrityViolationException race) {
      // Failed writes have rolled back before looking for the committed winner.
      return tx.execute(s -> decisions.findById(c.decisionId()).map(d -> replay(d,normalized,c.decisionId())).orElse(new M3ReviewAck(c.decisionId(),"CONFLICT")));
    }
  }
  private M3ReviewAck acceptLocked(M3ReviewCommand c,String normalized) {
    var prior=decisions.findById(c.decisionId());
    if(prior.isPresent()) return replay(prior.get(),normalized,c.decisionId());
    var p=payments.lockInternal(c.paymentId()).orElse(null);
    prior=decisions.findById(c.decisionId());
    if(prior.isPresent()) return replay(prior.get(),normalized,c.decisionId());
    if(p==null || p.flowVersion()!=1 || p.senderId()==null || p.status()!=PaymentLifecycleStatus.UNDER_REVIEW
        || !c.reviewReference().toString().equals(p.reviewReference())
        || !c.assessmentId().equals(p.reviewAssessmentId()) || !c.caseId().equals(p.reviewCaseId())
        || !c.paymentFingerprint().equals(p.reviewPaymentFingerprint())
        || decisions.findByReviewReference(c.reviewReference().toString()).isPresent()) return new M3ReviewAck(c.decisionId(),"CONFLICT");
    Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);
    var ack=new M3ReviewAck(c.decisionId(),"ACKNOWLEDGED");
    var saved=new M3ReviewDecision(c,normalized,encode(ack),now);
    decisions.saveAndFlush(saved);
    if("APPROVE".equals(c.decision())) p.acceptReview(c.decisionId(),now); else p.reject(now);
    payments.flush();
    return ack;
  }
  private M3ReviewAck replay(M3ReviewDecision saved,String normalized,UUID id) {
    if(!normalized.equals(saved.normalizedCommand())) return new M3ReviewAck(id,"CONFLICT");
    try { return json.readValue(saved.resultSnapshot(),M3ReviewAck.class); }
    catch(Exception malformed) { throw new IllegalStateException("Stored review acknowledgment is invalid",malformed); }
  }
  private String encode(Object value) {
    try { return json.writeValueAsString(value); } catch(Exception e) { throw new IllegalStateException("Cannot encode review identity",e); }
  }
  private static String normalizeReason(String value) { return value==null || value.strip().isEmpty()?null:value.strip(); }
  private static boolean valid(M3ReviewCommand c) {
    return c!=null && c.decisionId()!=null && c.caseId()!=null && c.assessmentId()!=null && c.paymentId()!=null
        && c.reviewReference()!=null && c.reviewerId()!=null && c.decidedAt()!=null
        && c.paymentFingerprint()!=null && c.paymentFingerprint().matches("[0-9a-f]{64}")
        && c.decision()!=null && Set.of("APPROVE","REJECT").contains(c.decision())
        && (c.reason()==null || c.reason().strip().length()<=500)
        && (!"REJECT".equals(c.decision()) || normalizeReason(c.reason())!=null);
  }
}
