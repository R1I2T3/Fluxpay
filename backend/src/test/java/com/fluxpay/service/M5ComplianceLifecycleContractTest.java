package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fluxpay.beans.*;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.*;
import com.fluxpay.repository.M5ScreeningStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class M5ComplianceLifecycleContractTest {
  final MemoryStore store=new MemoryStore();
  final Reader reader=new Reader();
  final PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
  final Clock clock=Clock.fixed(M5ComplianceRulesUnitTest.NOW,ZoneOffset.UTC);
  final CurrentUser admin=new CurrentUser(UUID.randomUUID(),"synthetic-admin@example.invalid","ADMIN");
  final M5ComplianceService service;
  M5ComplianceLifecycleContractTest() {
    when(tx.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
    service=new M5ComplianceService(store,reader,new M5ComplianceRulesEngine(M5ComplianceRulesUnitTest.settings()),tx,clock);
  }
  M5AssessmentRequest request(long seq) { return new M5AssessmentRequest(UUID.randomUUID(),seq,reader.snapshot.paymentId(),M5Fingerprints.payment(reader.snapshot)); }
  @Test void immutableReplayNeverReadsLivePaymentAndFreshCycleSeesNewRisk() {
    var a=request(1); var first=assertDoesNotThrow(() -> service.assessOutcome(a));
    assertFalse(first.replay()); assertEquals("REVIEW",first.response().screeningVerdict());
    reader.available=false;
    assertEquals(first.response(),service.assessOutcome(a).response());
    assertTrue(service.assessOutcome(a).replay()); assertEquals(1,reader.reads);
    reader.available=true; reader.snapshot=M5ComplianceRulesUnitTest.snapshot("10","USD","Family support",true,true,true,0L,"IN");
    var fresh=service.assess(request(4)); assertEquals("APPROVE",fresh.screeningVerdict());
    assertEquals(first.response(),service.assess(a)); assertEquals(2,reader.reads);
    assertEquals("STALE_ASSESSMENT",assertThrows(M5ApiException.class,() -> service.assess(request(3))).code());
    assertEquals(2,store.cases.size());
    assertEquals("ASSESSMENT_CONFLICT",assertThrows(M5ApiException.class,() -> service.assess(new M5AssessmentRequest(a.assessmentId(),2,a.paymentId(),a.expectedPaymentFingerprint()))).code());
  }
  @Test void onlyActivatedLatestReviewAcceptsDecisionAndExactReplaySurvivesLaterCycle() {
    var request=request(1); var assessment=assertDoesNotThrow(() -> service.assess(request));
    assertFalse(service.get(assessment.caseId(),admin).reviewable());
    assertEquals("CASE_CONFLICT",assertThrows(M5ApiException.class,() -> service.decide(assessment.caseId(),"APPROVE",null,admin)).code());
    UUID reference=UUID.randomUUID();
    assertTrue(service.recordDisposition(assessment.assessmentId(),"REVIEW_REQUIRED",reference).reviewable());
    var approved=service.decide(assessment.caseId(),"APPROVE","  checked  ",admin);
    assertEquals("APPROVED",approved.status()); assertEquals("REVIEW",approved.screeningVerdict());
    assertEquals("PENDING",approved.deliveryState()); assertNotNull(approved.decisionId());
    assertEquals(1,store.decisions.size());
    service.assess(request(2));
    assertEquals(approved.decisionId(),service.decide(assessment.caseId(),"APPROVE","checked",admin).decisionId());
    assertEquals("REVIEW_REQUIRED",service.recordDisposition(assessment.assessmentId(),"REVIEW_REQUIRED",reference).paymentDisposition());
    assertEquals(assessment,service.assess(request));
    assertEquals("CASE_CONFLICT",assertThrows(M5ApiException.class,() -> service.decide(assessment.caseId(),"REJECT","changed",admin)).code());
    assertEquals("CASE_CONFLICT",assertThrows(M5ApiException.class,() -> service.decide(assessment.caseId(),"APPROVE","checked",new CurrentUser(UUID.randomUUID(),"other@example.invalid","ADMIN"))).code());
  }
  @Test void proceedCannotBecomeReviewAndFirstSupersededCallbackFails() {
    var a=assertDoesNotThrow(() -> service.assess(request(1)));
    service.recordDisposition(a.assessmentId(),"PROCEED",null);
    assertFalse(service.get(a.caseId(),admin).reviewable());
    assertEquals("CASE_CONFLICT",assertThrows(M5ApiException.class,() -> service.decide(a.caseId(),"APPROVE",null,admin)).code());
    assertEquals("REVIEW_CONFLICT",assertThrows(M5ApiException.class,() -> service.recordDisposition(a.assessmentId(),"REVIEW_REQUIRED",UUID.randomUUID())).code());
    var b=service.assess(request(2)); service.assess(request(3));
    assertEquals("REVIEW_CONFLICT",assertThrows(M5ApiException.class,() -> service.recordDisposition(b.assessmentId(),"PROCEED",null)).code());
  }
  @Test void rejectionRequiresReasonAndNeverRewritesScreeningVerdict() {
    var a=assertDoesNotThrow(() -> service.assess(request(1)));
    service.recordDisposition(a.assessmentId(),"REVIEW_REQUIRED",UUID.randomUUID());
    assertEquals("REJECT_REASON_REQUIRED",assertThrows(M5ApiException.class,() -> service.decide(a.caseId(),"REJECT","  ",admin)).code());
    var rejected=service.decide(a.caseId(),"REJECT"," insufficient evidence ",admin);
    assertEquals("REJECTED",rejected.status()); assertEquals("BLOCK",rejected.verdict());
    assertEquals("REVIEW",service.assess(new M5AssessmentRequest(a.assessmentId(),1,a.paymentId(),a.expectedPaymentFingerprint())).screeningVerdict());
    assertEquals("insufficient evidence",rejected.decisionReason());
  }
  @Test void ownerLookupDoesNotDependOnRiskReaderAndForeignIsNotFound() {
    var a=assertDoesNotThrow(() -> service.assess(request(1))); reader.available=false;
    assertEquals(a.caseId(),service.passport(a.paymentId(),new CurrentUser(reader.snapshot.senderId(),"owner@example.invalid","USER")).caseId());
    assertEquals("CASE_NOT_FOUND",assertThrows(M5ApiException.class,() -> service.passport(a.paymentId(),new CurrentUser(UUID.randomUUID(),"foreign@example.invalid","USER"))).code());
    assertEquals(1,reader.reads);
  }
  @Test void lostAcknowledgmentRetriesImmutablePayloadAndStaleAckBecomesConflict() {
    var a=assertDoesNotThrow(() -> service.assess(request(1))); service.recordDisposition(a.assessmentId(),"REVIEW_REQUIRED",UUID.randomUUID());
    var decision=service.decide(a.caseId(),"APPROVE",null,admin);
    var effects=new HashMap<UUID,M5ReviewCommand>();
    M5ReviewDecisionSink sink=command -> { var prior=effects.putIfAbsent(command.decisionId(),command);
      if(prior==null) throw new IllegalStateException("synthetic acknowledgment loss");
      assertEquals(prior,command); return new M5DeliveryAck(command.decisionId(),"ACKNOWLEDGED"); };
    var delivery=new M5ReviewDeliveryService(store,sink,tx,clock);
    assertEquals(1,delivery.deliverPending(10));
    assertEquals("PENDING",store.decisions.get(decision.decisionId()).deliveryState());
    assertEquals(0,delivery.deliverPending(10));
    var later=new M5ReviewDeliveryService(store,sink,tx,Clock.offset(clock,Duration.ofMinutes(2)));
    assertEquals(1,later.deliverPending(10)); assertEquals(1,effects.size());
    assertEquals("ACKNOWLEDGED",store.decisions.get(decision.decisionId()).deliveryState());
    var b=service.assess(request(2)); service.recordDisposition(b.assessmentId(),"REVIEW_REQUIRED",UUID.randomUUID());
    var second=service.decide(b.caseId(),"REJECT","synthetic",admin);
    new M5ReviewDeliveryService(store,c -> new M5DeliveryAck(c.decisionId(),"CONFLICT"),tx,clock).deliverPending(10);
    assertEquals("CONFLICT",store.decisions.get(second.decisionId()).deliveryState());
    assertEquals("REJECTED",service.get(b.caseId(),admin).status());
  }
  static class Reader implements M5PaymentReader {
    M5PaymentSnapshot snapshot=M5ComplianceRulesUnitTest.snapshot("10","USD","Family support",true,false,true,0L,"IN");
    boolean available=true; int reads;
    public M5PaymentSnapshot readForAssessment(UUID id) { reads++; if(!available) throw new AssertionError("Replay read live risk data"); return snapshot; }
    public UUID ownerOf(UUID id) { return snapshot.senderId(); }
  }
  static class MemoryStore implements M5ScreeningStore {
    final Map<UUID,M5ScreeningCase> cases=new HashMap<>(); final Map<UUID,M5ScreeningHead> heads=new HashMap<>();
    final Map<UUID,M5ReviewDecision> decisions=new HashMap<>();
    public Optional<M5ScreeningCase> byAssessment(UUID id){return cases.values().stream().filter(c -> c.assessment().assessmentId().equals(id)).findFirst();}
    public Optional<M5ScreeningCase> byCase(UUID id,boolean lock){return Optional.ofNullable(cases.get(id));}
    public Optional<M5ScreeningHead> head(UUID id,boolean lock){return Optional.ofNullable(heads.get(id));}
    public void createHead(UUID id){heads.put(id,new M5ScreeningHead(id,null,0,0));}
    public void insertCase(M5ScreeningCase c){cases.put(c.assessment().caseId(),c);}
    public void updateCase(M5ScreeningCase c){insertCase(c);}
    public void publishHead(M5ScreeningHead h){heads.put(h.paymentId(),h);}
    public Optional<M5ReviewDecision> decisionForCase(UUID id){return decisions.values().stream().filter(d -> d.command().caseId().equals(id)).findFirst();}
    public Optional<M5ReviewDecision> decision(UUID id,boolean lock){return Optional.ofNullable(decisions.get(id));}
    public void insertDecision(M5ReviewDecision d){decisions.put(d.command().decisionId(),d);}
    public void updateDelivery(M5ReviewDecision d){insertDecision(d);}
    public List<M5ReviewDecision> pending(Instant now,int limit){return decisions.values().stream().filter(d -> d.deliveryState().equals("PENDING")&&!d.nextAttemptAt().isAfter(now)&&d.retryCount()<8).limit(limit).toList();}
    public List<M5ScreeningCase> list(String status,String risk,Boolean reviewable,int page,int size){return cases.values().stream().skip((long)page*size).limit(size).toList();}
    public long count(String status,String risk,Boolean reviewable){return cases.size();}
  }
}
