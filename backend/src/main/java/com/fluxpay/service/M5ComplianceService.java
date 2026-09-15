package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.dto.*;
import com.fluxpay.repository.M5ScreeningStore;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Immutable screening observations. Does not change payments or call embedding providers. */
public class M5ComplianceService implements M5AssessmentPort, M5CaseContextReader {
  private final M5ScreeningStore store;
  private final M5PaymentReader reader;
  private final M5ComplianceRulesEngine rules;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final M5ActiveReviewGuard activeReviews;
  public M5ComplianceService(M5ScreeningStore store, M5PaymentReader reader,
      M5ComplianceRulesEngine rules, PlatformTransactionManager transactions, Clock clock) {
    this(store,reader,rules,transactions,clock,(payment,caseId) -> false);
  }
  public M5ComplianceService(M5ScreeningStore store, M5PaymentReader reader,
      M5ComplianceRulesEngine rules, PlatformTransactionManager transactions, Clock clock,
      M5ActiveReviewGuard activeReviews) {
    this.store=Objects.requireNonNull(store); this.reader=Objects.requireNonNull(reader);
    this.rules=Objects.requireNonNull(rules); this.clock=Objects.requireNonNull(clock);
    this.activeReviews=Objects.requireNonNull(activeReviews);
    tx=new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    tx.setTimeout(5);
  }
  public M5AssessmentResponse assess(M5AssessmentRequest request) { return assessOutcome(request).response(); }
  public M5AssessmentOutcome assessOutcome(M5AssessmentRequest request) {
    validate(request);
    String fingerprint=M5Fingerprints.request(request);
    var prior=transaction(() -> store.byAssessment(request.assessmentId()));
    if(prior.isPresent()) return replay(prior.get(),fingerprint);
    // Suspend any caller transaction so independently committed evidence never contains
    // upstream changes that the caller may later roll back. No M5 locks are held here.
    M5PaymentSnapshot snapshot=transaction(() -> reader.readForAssessment(request.paymentId()));
    if(snapshot==null || !request.paymentId().equals(snapshot.paymentId()))
      throw error(503,"PAYMENT_DATA_UNAVAILABLE","Reader did not return the requested payment");
    if(!request.expectedPaymentFingerprint().equals(M5Fingerprints.payment(snapshot)))
      throw error(409,"STALE_ASSESSMENT","Payment facts changed; obtain a fresh payment fingerprint");
    M5RuleResult result=rules.evaluate(snapshot);
    var response=new M5AssessmentResponse(request.assessmentId(),request.assessmentSequence(),
        request.paymentId(),request.expectedPaymentFingerprint(),UUID.randomUUID(),result.risk(),
        result.screeningVerdict(),result.reasons(),now(),result.ruleVersion(),result.ruleConfigHash());
    var value=new M5ScreeningCase(response,snapshot,fingerprint,result.status(),result.screeningVerdict(),
        result.suggestedAction(),null,null,null,null,null,0);
    for(int attempt=0;attempt<3;attempt++) {
      try {
        return transaction(() -> {
          var existing=store.byAssessment(request.assessmentId());
          if(existing.isPresent()) return replay(existing.get(),fingerprint);
          var head=store.head(request.paymentId(),true).orElseGet(() -> {
            store.createHead(request.paymentId());
            return store.head(request.paymentId(),true).orElseThrow();
          });
          existing=store.byAssessment(request.assessmentId());
          if(existing.isPresent()) return replay(existing.get(),fingerprint);
          requireFresh(request,head);
          if(head.latestCaseId()!=null) {
            var latest=caseById(head.latestCaseId(),true);
            if("REVIEW_REQUIRED".equals(latest.paymentDisposition())
                && ("UNDER_REVIEW".equals(latest.status()) || activeReviews.isActive(request.paymentId(),head.latestCaseId())))
              throw error(409,"ACTIVE_REVIEW","The active M3 review must be resolved before another assessment is published");
          }
          store.insertCase(value);
          store.publishHead(new M5ScreeningHead(request.paymentId(),response.caseId(),
              request.assessmentSequence(),head.version()+1));
          return new M5AssessmentOutcome(response,false);
        });
      } catch(DataIntegrityViolationException | ConcurrencyFailureException race) {
        // The failed publication transaction has rolled back before inspecting the winner.
        var winner=transaction(() -> store.byAssessment(request.assessmentId()));
        if(winner.isPresent()) return replay(winner.get(),fingerprint);
        if(attempt==2) throw error(409,"ASSESSMENT_CONFLICT","Concurrent assessment; retry the same request");
      }
    }
    throw new IllegalStateException("Unreachable assessment retry state");
  }
  /** Trusted upstream callback, deliberately not an HTTP activation shortcut. */
  public M5CaseResponse recordDisposition(UUID assessmentId,String disposition,UUID reference) {
    if(assessmentId==null || disposition==null || !Set.of("REVIEW_REQUIRED","PROCEED","BLOCKED").contains(disposition)
        || ("REVIEW_REQUIRED".equals(disposition)!=(reference!=null)))
      throw error(400,"VALIDATION","Valid disposition and matching review reference required");
    try {
      return transaction(() -> {
        var initial=store.byAssessment(assessmentId).orElseThrow(M5ComplianceService::missingCase);
        var head=store.head(initial.assessment().paymentId(),true).orElseThrow(M5ComplianceService::missingCase);
        var value=caseById(initial.assessment().caseId(),true);
        if(value.paymentDisposition()!=null) {
          if(value.paymentDisposition().equals(disposition) && Objects.equals(value.reviewReference(),reference))
            return response(value,head);
          throw error(409,"REVIEW_CONFLICT","A different disposition was already recorded");
        }
        if(!isLatest(value,head) || ("REVIEW_REQUIRED".equals(disposition)
            && !"REVIEW".equals(value.assessment().screeningVerdict())))
          throw error(409,"REVIEW_CONFLICT","Only the latest REVIEW assessment can activate review");
        var updated=new M5ScreeningCase(value.assessment(),value.snapshot(),value.requestFingerprint(),
            value.status(),value.verdict(),value.suggestedAction(),disposition,reference,
            value.decidedBy(),value.decidedAt(),value.decisionReason(),value.version()+1);
        store.updateCase(updated);
        return response(updated,head);
      });
    } catch(DataIntegrityViolationException | ConcurrencyFailureException race) {
      throw error(409,"REVIEW_CONFLICT","Review reference was used or concurrently changed");
    }
  }
  public M5CaseResponse decide(UUID caseId,String action,String reason,CurrentUser actor) {
    requireAdmin(actor);
    if(action==null || !Set.of("APPROVE","REJECT").contains(action))
      throw error(400,"VALIDATION","Decision must be APPROVE or REJECT");
    String normalized=reason==null || reason.strip().isEmpty()?null:reason.strip();
    if("REJECT".equals(action) && normalized==null)
      throw error(400,"REJECT_REASON_REQUIRED","Rejection requires a reason");
    if(normalized!=null && normalized.length()>500)
      throw error(400,"VALIDATION","Decision reason must contain at most 500 characters");
    try {
      return transaction(() -> {
        var initial=caseById(caseId,false);
        var head=store.head(initial.assessment().paymentId(),true).orElseThrow(M5ComplianceService::missingCase);
        var value=caseById(caseId,true);
        var prior=store.decisionForCase(caseId);
        if(prior.isPresent()) {
          var command=prior.get().command();
          if(action.equals(command.decision()) && Objects.equals(normalized,command.reason())
              && actor.userId().equals(command.reviewerId())) return response(value,head);
          throw error(409,"CASE_CONFLICT","A different decision was already recorded");
        }
        if(!reviewable(value,head)) throw error(409,"CASE_CONFLICT","Case is not the latest active review");
        Instant now=now();
        var updated=new M5ScreeningCase(value.assessment(),value.snapshot(),value.requestFingerprint(),
            "APPROVE".equals(action)?"APPROVED":"REJECTED","APPROVE".equals(action)?"APPROVE":"BLOCK",
            value.suggestedAction(),value.paymentDisposition(),value.reviewReference(),actor.userId(),
            now,normalized,value.version()+1);
        var command=new M5ReviewCommand(UUID.randomUUID(),caseId,value.assessment().assessmentId(),
            value.assessment().paymentId(),value.reviewReference(),value.assessment().expectedPaymentFingerprint(),
            action,actor.userId(),now,normalized);
        store.updateCase(updated);
        store.insertDecision(new M5ReviewDecision(command,"PENDING",0,now,null));
        return response(updated,head);
      });
    } catch(DataIntegrityViolationException | ConcurrencyFailureException race) {
      throw error(409,"CASE_CONFLICT","Concurrent decision; reload the case");
    }
  }
  public M5CaseResponse get(UUID caseId,CurrentUser actor) {
    requireAdmin(actor);
    return transaction(() -> {
      var value=caseById(caseId,false);
      return response(value,store.head(value.assessment().paymentId(),false).orElse(null));
    });
  }
  public M5CaseResponse passport(UUID paymentId,CurrentUser actor) {
    return passport(paymentId,actor,
        new M5WorkDeadline(java.time.Duration.ofSeconds(5),"CASE_CONTEXT_TIMEOUT"));
  }
  private M5CaseResponse passport(UUID paymentId,CurrentUser actor,M5WorkDeadline deadline) {
    requireIdentity(actor);
    if(paymentId==null) throw error(400,"VALIDATION","Payment ID required");
    return contextTransaction(deadline,() -> {
      if(!"ADMIN".equals(actor.role()) && !actor.userId().equals(reader.ownerOf(paymentId)))
        throw missingCase();
      var head=store.head(paymentId,false).orElseThrow(M5ComplianceService::missingCase);
      if(head.latestCaseId()==null) throw missingCase();
      return response(caseById(head.latestCaseId(),false),head);
    });
  }
  public long latestSequence(UUID paymentId) {
    if(paymentId==null) throw error(400,"VALIDATION","Payment ID required");
    return transaction(() -> store.head(paymentId,false).map(M5ScreeningHead::latestSequence).orElse(0L));
  }
  public M5CasePage list(String status,String risk,Boolean reviewable,int page,int size,CurrentUser actor) {
    requireAdmin(actor);
    if(page<0 || size<1 || size>100
        || (status!=null && !Set.of("UNDER_REVIEW","PROCESSING","APPROVED","REJECTED").contains(status))
        || (risk!=null && !Set.of("LOW","MEDIUM","HIGH").contains(risk)))
      throw error(400,"VALIDATION","Invalid case filters or pagination");
    return transaction(() -> new M5CasePage(store.list(status,risk,reviewable,page,size).stream()
        .map(value -> response(value,store.head(value.assessment().paymentId(),false).orElse(null))).toList(),
        page,size,store.count(status,risk,reviewable)));
  }
  public Map<String,Object> context(UUID paymentId,CurrentUser actor) {
    return context(paymentId,actor,
        new M5WorkDeadline(java.time.Duration.ofSeconds(5),"CASE_CONTEXT_TIMEOUT"));
  }
  @Override
  public Map<String,Object> context(UUID paymentId,CurrentUser actor,M5WorkDeadline deadline) {
    var value=passport(paymentId,actor,deadline);
    var context=new LinkedHashMap<String,Object>();
    context.put("paymentId",value.paymentId()); context.put("caseId",value.caseId());
    context.put("risk",value.risk()); context.put("reasons",value.reasons()); context.put("status",value.status());
    context.put("screeningVerdict",value.screeningVerdict()); context.put("verdict",value.verdict());
    context.put("paymentDisposition",value.paymentDisposition()); context.put("reviewable",value.reviewable());
    context.put("suggestedAction",value.suggestedAction());
    return Collections.unmodifiableMap(context);
  }
  private <T> T contextTransaction(M5WorkDeadline deadline,Supplier<T> work) {
    deadline.check();
    PlatformTransactionManager manager=Objects.requireNonNull(tx.getTransactionManager());
    var bounded=new TransactionTemplate(manager);
    bounded.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    bounded.setTimeout(deadline.sqlTimeoutSeconds());
    T result=bounded.execute(ignored -> {
      rebudgetContextConnection(deadline,manager);
      return work.get();
    });
    deadline.check();
    return result;
  }
  private static void rebudgetContextConnection(M5WorkDeadline deadline,
    PlatformTransactionManager manager) {
    deadline.check();
    long remainingMillis=deadline.remaining().toMillis();
    if(remainingMillis<=1000) throw deadline.expired();
    javax.sql.DataSource source=null;
    if(manager instanceof DataSourceTransactionManager jdbc) source=jdbc.getDataSource();
    else if(manager instanceof JpaTransactionManager jpa) source=jpa.getDataSource();
    if(source==null) return;
    Object resource=TransactionSynchronizationManager.getResource(source);
    if(!(resource instanceof ConnectionHolder holder))
      throw error(503,"DATABASE_UNAVAILABLE","Bounded JDBC context is unavailable");
    holder.setTimeoutInMillis(Math.min(3000L,remainingMillis-1000L));
  }
  private M5CaseResponse response(M5ScreeningCase value,M5ScreeningHead head) {
    var a=value.assessment(); var decision=store.decisionForCase(a.caseId()).orElse(null);
    return new M5CaseResponse(a.caseId(),a.paymentId(),a.assessmentId(),a.assessmentSequence(),a.risk(),
        value.status(),a.screeningVerdict(),value.verdict(),value.paymentDisposition(),value.reviewReference(),
        reviewable(value,head),a.reasons(),value.suggestedAction(),a.assessedAt(),value.decidedBy(),
        value.decidedAt(),value.decisionReason(),decision==null?null:decision.command().decisionId(),
        decision==null?null:decision.deliveryState());
  }
  private M5ScreeningCase caseById(UUID id,boolean lock) {
    if(id==null) throw error(400,"VALIDATION","Case ID required");
    return store.byCase(id,lock).orElseThrow(M5ComplianceService::missingCase);
  }
  private boolean isLatest(M5ScreeningCase value,M5ScreeningHead head) {
    return head!=null && value.assessment().caseId().equals(head.latestCaseId());
  }
  private boolean reviewable(M5ScreeningCase value,M5ScreeningHead head) {
    return isLatest(value,head) && "UNDER_REVIEW".equals(value.status())
        && "REVIEW_REQUIRED".equals(value.paymentDisposition()) && value.reviewReference()!=null;
  }
  private M5AssessmentOutcome replay(M5ScreeningCase value,String fingerprint) {
    if(!fingerprint.equals(value.requestFingerprint()))
      throw error(409,"ASSESSMENT_CONFLICT","Assessment ID was used with a different request");
    return new M5AssessmentOutcome(value.assessment(),true);
  }
  private void requireFresh(M5AssessmentRequest request,M5ScreeningHead head) {
    if(head!=null && request.assessmentSequence()<=head.latestSequence())
      throw error(409,"STALE_ASSESSMENT","Assessment sequence must exceed the latest sequence");
  }
  private void validate(M5AssessmentRequest request) {
    if(request==null || request.assessmentId()==null || request.paymentId()==null
        || request.assessmentSequence()<1 || request.expectedPaymentFingerprint()==null
        || !request.expectedPaymentFingerprint().matches("[0-9a-f]{64}"))
      throw error(400,"VALIDATION","Assessment IDs, positive sequence and SHA-256 fingerprint required");
  }
  private static void requireIdentity(CurrentUser actor) {
    if(actor==null || actor.userId()==null) throw error(401,"AUTH_REQUIRED","Authentication required");
  }
  private static void requireAdmin(CurrentUser actor) {
    requireIdentity(actor);
    if(!"ADMIN".equals(actor.role())) throw error(403,"FORBIDDEN","ADMIN access required");
  }
  private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
  private <T> T transaction(Supplier<T> work) { return tx.execute(ignored -> work.get()); }
  private static M5ApiException missingCase() { return error(404,"CASE_NOT_FOUND","Compliance case not found"); }
  private static M5ApiException error(int status,String code,String message) { return new M5ApiException(status,code,message); }
}
