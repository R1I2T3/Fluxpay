package com.fluxpay.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.*;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.*;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Real JPA/JDBC and M2 journal transactions against disposable H2; not Oracle acceptance. */
class M3M5TransactionIntegrationTest {
  LocalContainerEntityManagerFactoryBean factory;
  JpaTransactionManager manager;
  TransactionTemplate tx;
  JdbcTemplate jdbc;
  PaymentRepository payments; PaymentQuoteRepository quotes; RecipientRepository recipients;
  M3PaymentOperationRepository operations; OutboxEventRepository events;
  M3OutboxDeliveryRepository deliveries; M3ReviewDecisionRepository decisions;
  WalletRepository wallets; LedgerEntryRepository ledger; jakarta.persistence.EntityManager entities;
  M3PostingPort postingOverride;
  M5ScreeningStore store;
  ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  Instant initial=Instant.parse("2026-09-13T10:00:00Z");
  AtomicReference<Instant> instant=new AtomicReference<>(initial);
  Clock clock=new Clock() {
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId z) { return this; }
    public Instant instant() { return instant.get(); }
  };
  Payment p; UUID quoteId; int assessments;
  @BeforeEach void setup() {
    var ds=new DriverManagerDataSource("jdbc:h2:mem:m3_"+UUID.randomUUID()+";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=3000","sa","");
    jdbc=new JdbcTemplate(ds);
    factory=new LocalContainerEntityManagerFactoryBean(); factory.setDataSource(ds);
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setManagedTypes(PersistenceManagedTypes.of(Payment.class.getName(),Recipient.class.getName(),PaymentQuote.class.getName(),M3PaymentOperation.class.getName(),OutboxEvent.class.getName(),M3OutboxDelivery.class.getName(),M3ReviewDecision.class.getName(),Wallet.class.getName(),LedgerEntry.class.getName()));
    factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.type.preferred_uuid_jdbc_type","BINARY"));
    factory.afterPropertiesSet();
    manager=new JpaTransactionManager(factory.getObject()); manager.setDataSource(ds); tx=new TransactionTemplate(manager);
    entities=SharedEntityManagerCreator.createSharedEntityManager(factory.getObject());
    var repositories=new JpaRepositoryFactory(entities);
    repositories.addRepositoryProxyPostProcessor((proxy,info) -> proxy.addAdvice(new org.springframework.dao.support.PersistenceExceptionTranslationInterceptor(new org.springframework.orm.jpa.vendor.HibernateJpaDialect())));
    payments=repositories.getRepository(PaymentRepository.class); quotes=repositories.getRepository(PaymentQuoteRepository.class);
    recipients=repositories.getRepository(RecipientRepository.class); operations=repositories.getRepository(M3PaymentOperationRepository.class);
    events=repositories.getRepository(OutboxEventRepository.class); deliveries=repositories.getRepository(M3OutboxDeliveryRepository.class);
    decisions=repositories.getRepository(M3ReviewDecisionRepository.class);
    wallets=repositories.getRepository(WalletRepository.class); ledger=repositories.getRepository(LedgerEntryRepository.class);
    jdbc.execute("""
        CREATE TABLE screening_cases (
          id RAW(16) PRIMARY KEY, payment_id RAW(16) NOT NULL,
          verdict VARCHAR2(20) NOT NULL, created_at TIMESTAMP NOT NULL,
          assessment_id RAW(16) NOT NULL UNIQUE, assessment_sequence NUMBER(19) NOT NULL,
          request_fingerprint VARCHAR2(64) NOT NULL, payment_fingerprint VARCHAR2(64) NOT NULL,
          risk VARCHAR2(10) NOT NULL, status VARCHAR2(20) NOT NULL,
          screening_verdict VARCHAR2(20) NOT NULL, risk_reasons CLOB NOT NULL,
          suggested_action VARCHAR2(400) NOT NULL, decided_by RAW(16), decided_at TIMESTAMP,
          decision_reason VARCHAR2(500), version NUMBER(10) DEFAULT 0 NOT NULL,
          assessment_snapshot CLOB NOT NULL, assessment_response CLOB NOT NULL,
          rule_version VARCHAR2(100) NOT NULL, rule_config_hash VARCHAR2(64) NOT NULL,
          review_reference RAW(16) UNIQUE, payment_disposition VARCHAR2(20),
          UNIQUE(payment_id, assessment_sequence), UNIQUE(id,payment_id,assessment_sequence),
          UNIQUE(id,assessment_id,payment_id,review_reference),
          CHECK (status IN ('UNDER_REVIEW','PROCESSING','APPROVED','REJECTED')),
          CHECK (risk IN ('LOW','MEDIUM','HIGH')), CHECK (version >= 0),
          CHECK ((status='UNDER_REVIEW' AND verdict='REVIEW')
            OR (status IN ('PROCESSING','APPROVED') AND verdict='APPROVE')
            OR (status='REJECTED' AND verdict='BLOCK'))
        )
        """);
    jdbc.execute("""
        CREATE TABLE m5_screening_heads (
          payment_id RAW(16) PRIMARY KEY, latest_case_id RAW(16),
          latest_sequence NUMBER(19) DEFAULT 0 NOT NULL, version NUMBER(10) DEFAULT 0 NOT NULL,
          FOREIGN KEY(latest_case_id,payment_id,latest_sequence)
            REFERENCES screening_cases(id,payment_id,assessment_sequence)
        )
        """);
    jdbc.execute("""
        CREATE TABLE m5_review_decisions (
          id RAW(16) PRIMARY KEY, case_id RAW(16) NOT NULL UNIQUE,
          review_reference RAW(16) NOT NULL UNIQUE, payment_id RAW(16) NOT NULL,
          assessment_id RAW(16) NOT NULL, payment_fingerprint VARCHAR2(64) NOT NULL,
          decision VARCHAR2(10) NOT NULL, reason VARCHAR2(500), reviewer_id RAW(16) NOT NULL,
          decided_at TIMESTAMP NOT NULL, delivery_state VARCHAR2(20) NOT NULL,
          retry_count NUMBER(10) NOT NULL, next_attempt_at TIMESTAMP NOT NULL,
          last_error_code VARCHAR2(100),
          FOREIGN KEY(case_id,assessment_id,payment_id,review_reference)
            REFERENCES screening_cases(id,assessment_id,payment_id,review_reference)
        )
        """);

    jdbc.execute("CREATE TABLE test_postings (payment_id RAW(16), amount NUMBER(19,4))");
    store=new JdbcM5ScreeningStore(jdbc,json);
    UUID owner=UUID.randomUUID();
    var customer=new Wallet(owner,"USD",WalletAccountRole.CUSTOMER);
    var r=new Recipient(UUID.randomUUID(),owner,"Synthetic","synthetic","Bank","IN","INR",RecipientStatus.ACTIVE,initial);
    p=new Payment(UUID.randomUUID(),owner,customer.getId(),r,new BigDecimal("10"),"USD","INR",PaymentPurpose.FAMILY_SUPPORT,QuoteRoute.BALANCED,"{\"country\":\"IN\",\"currency\":\"INR\"}",initial);
    tx.executeWithoutResult(s -> { wallets.saveAndFlush(customer); recipients.saveAndFlush(r); payments.saveAndFlush(p); });
    freshQuote();
  }
  @AfterEach void close() { if(factory!=null) factory.destroy(); }
  Payment current() { return payments.findById(p.id()).orElseThrow(); }
  void freshQuote() {
    quoteId=UUID.randomUUID();
    tx.executeWithoutResult(s -> {
      var locked=payments.lockInternal(p.id()).orElseThrow(); int generation=locked.nextQuoteGeneration();
      quotes.saveAndFlush(new PaymentQuote(quoteId,p.id(),generation,QuoteRoute.BALANCED,BigDecimal.ONE,0,BigDecimal.ONE,BigDecimal.ONE,new BigDecimal("9"),5,true,clock.instant(),clock.instant().plusSeconds(1200)));
      locked.quoted(generation,clock.instant());
    });
  }
  M3PaymentAssessment observe(M3PaymentFacts facts,ScreeningVerdict verdict) {
    assessments++;
    return tx.execute(s -> {
      var head=store.head(facts.paymentId(),true).orElseGet(() -> { store.createHead(facts.paymentId()); return store.head(facts.paymentId(),true).orElseThrow(); });
      String fp="a".repeat(64); UUID id=UUID.randomUUID(), caseId=UUID.randomUUID();
      var a=new M5AssessmentResponse(id,head.latestSequence()+1,facts.paymentId(),fp,caseId,"HIGH",verdict.name(),List.of(),clock.instant(),"test","b".repeat(64));
      String status=verdict==ScreeningVerdict.REVIEW?"UNDER_REVIEW":verdict==ScreeningVerdict.BLOCK?"REJECTED":"PROCESSING";
      var snapshot=new M5PaymentSnapshot(facts.paymentId(),facts.senderId(),facts.walletId(),facts.recipientId(),facts.amount(),facts.sourceCurrency(),facts.payoutCurrency(),facts.purpose(),true,facts.recipientSnapshot(),facts.recipientVersion(),"IN",true,true,0L,clock.instant(),clock.instant(),clock.instant().truncatedTo(java.time.temporal.ChronoUnit.DAYS),"UTC","ALL_ATTEMPTS");
      store.insertCase(new M5ScreeningCase(a,snapshot,"c".repeat(64),status,verdict.name(),"Synthetic test",null,null,null,null,null,0));
      store.publishHead(new M5ScreeningHead(facts.paymentId(),caseId,a.assessmentSequence(),head.version()+1));
      return new M3PaymentAssessment(id,caseId,a.assessmentSequence(),fp,verdict,facts);
    });
  }
  PaymentConfirmationService service(ScreeningVerdict verdict,String fault) {
    return service(verdict,fault,null);
  }
  PaymentConfirmationService service(ScreeningVerdict verdict,String fault,M3PaymentCompliancePort compliance) {
    var joining=new M5JoiningDispositionService(store,manager);
    M5PaymentDispositionPort disposition=new M5PaymentDispositionPort() {
      public void lockAndValidate(M3PaymentAssessment a) { joining.lockAndValidate(a); }
      public void record(M3PaymentAssessment a,String d,UUID ref) {
        joining.record(a,d,ref); if("disposition".equals(fault)) throw fail();
      }
    };
    M3PostingPort posting=(payment,owner,wallet,currency,gross,fee,expiry) -> {
      jdbc.update("INSERT INTO test_postings VALUES (?,?)",com.fluxpay.common.util.UuidRawCodec.toBytes(payment),gross);
      if("posting".equals(fault)) throw fail();
      return new M3PostingAccounts(wallet,UUID.randomUUID(),UUID.randomUUID());
    };
    if(postingOverride!=null) posting=postingOverride;
    var outbox=events;
    if("outbox".equals(fault)) {
      outbox=mock(OutboxEventRepository.class);
      when(outbox.save(any())).thenAnswer(call -> { events.save(call.getArgument(0)); throw fail(); });
    }
    var ops=operations;
    if("operation".equals(fault)) {
      ops=mock(M3PaymentOperationRepository.class);
      when(ops.saveAndFlush(any())).thenAnswer(call -> {
        M3PaymentOperation op=call.getArgument(0); var saved=operations.saveAndFlush(op);
        if(op.outcomeStatus()!=0) throw fail(); return saved;
      });
    }
    var worker=new M3ConfirmationWorker(payments,quotes,recipients,user -> true,posting,clock,ops,outbox,deliveries,json,disposition,decisions,manager);
    return new PaymentConfirmationService(payments,operations,compliance==null?facts -> observe(facts,verdict):compliance,worker,json);
  }
  M3BusinessException fail() { return new M3BusinessException(HttpStatus.CONFLICT,"INJECTED","Synthetic transactional failure"); }
  PaymentResponse confirm(PaymentConfirmationService service,String key) {
    return service.confirm(p.senderId(),p.id(),new ConfirmPaymentRequest(quoteId),key);
  }
  long postings() { return jdbc.queryForObject("SELECT COUNT(*) FROM test_postings",Long.class); }
  M3ReviewCommand command(String action) {
    var held=current();
    return new M3ReviewCommand(UUID.randomUUID(),held.reviewCaseId(),held.reviewAssessmentId(),held.id(),UUID.fromString(held.reviewReference()),held.reviewPaymentFingerprint(),action,UUID.randomUUID(),clock.instant(),"Synthetic review");
  }
  M3ReviewService receiver() { return new M3ReviewService(payments,decisions,json,clock,manager); }
  @Test void reviewAndBlockCommitWithoutPostingAndOldReviewReplaysAfterQuoteExpiry() throws Exception {
    var service=service(ScreeningVerdict.REVIEW,null);
    var held=confirm(service,"review"); assertEquals(PaymentLifecycleStatus.UNDER_REVIEW,held.status());
    assertEquals(0,postings()); assertEquals(1,events.count());
    var event=events.findAll().get(0); assertEquals(event.id().toString(),json.readTree(event.payload()).path("eventId").asText());
    instant.set(initial.plusSeconds(2000)); assertEquals(held,confirm(service,"review")); assertEquals(1,assessments);
    var command=command("APPROVE"); assertEquals("ACKNOWLEDGED",receiver().accept(command).status()); freshQuote();
    var blocked=assertThrows(M3BusinessException.class,() -> confirm(service(ScreeningVerdict.BLOCK,null),"blocked"));
    assertEquals("PAYMENT_BLOCKED",blocked.code()); assertEquals(PaymentLifecycleStatus.REJECTED,current().status());
    assertEquals(0,postings()); assertNull(current().approvalConsumedAt());
    assertEquals("PAYMENT_BLOCKED",assertThrows(M3BusinessException.class,() -> confirm(service(ScreeningVerdict.BLOCK,null),"blocked")).code());
  }
  @Test void exactDecisionReplayCannotRenewOrRecreateSpentReceiptAndChangedPayloadConflicts() {
    var service=service(ScreeningVerdict.REVIEW,null); var oldQuote=quoteId;
    confirm(service,"old"); var command=command("APPROVE"); var receiver=receiver();
    var ack=receiver.accept(command); assertEquals("ACKNOWLEDGED",ack.status());
    var expiry=current().approvalExpiresAt(); assertEquals(initial.plusSeconds(900),expiry);
    instant.set(initial.plusSeconds(899)); assertEquals(ack,receiver.accept(command)); assertEquals(expiry,current().approvalExpiresAt());
    var changed=new M3ReviewCommand(command.decisionId(),command.caseId(),command.assessmentId(),command.paymentId(),command.reviewReference(),command.paymentFingerprint(),command.decision(),UUID.randomUUID(),command.decidedAt(),command.reason());
    assertEquals("CONFLICT",receiver.accept(changed).status()); assertEquals(1,decisions.count());
    assertNull(current().currentQuoteGeneration()); freshQuote();
    assertEquals(PaymentLifecycleStatus.PROCESSING,confirm(service,"fresh").status()); assertEquals(1,postings());
    assertNotNull(current().approvalConsumedAt()); assertNotNull(decisions.findById(command.decisionId()).orElseThrow().receiptConsumedAt());
    assertEquals(ack,receiver.accept(command)); assertNotNull(current().approvalConsumedAt()); assertEquals(expiry,current().approvalExpiresAt());
    assertEquals(PaymentLifecycleStatus.UNDER_REVIEW,service.confirm(p.senderId(),p.id(),new ConfirmPaymentRequest(oldQuote),"old").status());
  }
  @Test void expiredReceiptCannotOverrideNewReviewAndRejectNeverPosts() {
    confirm(service(ScreeningVerdict.REVIEW,null),"old"); receiver().accept(command("APPROVE"));
    instant.set(initial.plusSeconds(900)); freshQuote();
    assertEquals(PaymentLifecycleStatus.UNDER_REVIEW,confirm(service(ScreeningVerdict.REVIEW,null),"new").status());
    assertEquals("ACKNOWLEDGED",receiver().accept(command("REJECT")).status());
    assertEquals(PaymentLifecycleStatus.REJECTED,current().status()); assertEquals(0,postings());
  }
  @ParameterizedTest @ValueSource(strings={"posting","disposition","outbox","operation"})
  void failuresAfterFinancialAndDurableWritesRollEverythingBackIncludingReceipt(String fault) {
    confirm(service(ScreeningVerdict.REVIEW,null),"review"); var approved=command("APPROVE"); receiver().accept(approved);
    freshQuote(); long beforeEvents=events.count(); long beforeOperations=operations.count();
    assertEquals("INJECTED",assertThrows(M3BusinessException.class,() -> confirm(service(ScreeningVerdict.REVIEW,fault),"retryable")).code());
    assertEquals(PaymentLifecycleStatus.QUOTED,current().status()); assertEquals(0,postings());
    assertNull(current().approvalConsumedAt()); assertNull(decisions.findById(approved.decisionId()).orElseThrow().receiptConsumedAt());
    assertEquals(beforeEvents,events.count()); assertEquals(beforeOperations,operations.count());
    var head=store.head(p.id(),false).orElseThrow();
    assertNull(store.byCase(head.latestCaseId(),false).orElseThrow().paymentDisposition(),"Committed observation remains inactive after mutation rollback");
    assertEquals(PaymentLifecycleStatus.PROCESSING,confirm(service(ScreeningVerdict.REVIEW,null),"retryable").status());
    assertEquals(1,postings());
  }
  @Test void joiningDispositionRequiresAnActualTransaction() {
    var a=observe(M3PaymentFacts.from(current()),ScreeningVerdict.APPROVE);
    assertThrows(IllegalTransactionStateException.class,() -> new M5JoiningDispositionService(store,manager).record(a,"PROCEED",null));
    assertNull(store.byCase(a.caseId(),false).orElseThrow().paymentDisposition());
  }
  @Test void actualM2JournalBalancesAndRollsBackWithM3OutboxFailureBeforeSuccessfulRetry() {
    UUID system=UUID.randomUUID();
    var clearing=new Wallet(system,"USD",WalletAccountRole.PAYOUT_CLEARING);
    var fee=new Wallet(system,"USD",WalletAccountRole.FEE_REVENUE);
    var funding=new Wallet(system,"USD",WalletAccountRole.DEMO_CLEARING);
    var context=new LedgerPostingContext();
    var journal=new LedgerJournalService(new PersistentLedgerWriter(wallets,ledger,context),context);
    tx.executeWithoutResult(s -> {
      wallets.saveAndFlush(clearing); wallets.saveAndFlush(fee); wallets.saveAndFlush(funding);
      journal.post("synthetic-funding",List.of(
          new LedgerJournalLine(funding.getId(),"DEBIT",new BigDecimal("100.0000"),"USD","synthetic-funding:debit","Synthetic fixture funding"),
          new LedgerJournalLine(p.sourceWalletId(),"CREDIT",new BigDecimal("100.0000"),"USD","synthetic-funding:credit","Synthetic fixture funding")));
    });
    postingOverride=new M3M2PostingAdapter(new M3M2WalletAdapter(wallets,entities,system),journal,clock);
    assertEquals("INJECTED",assertThrows(M3BusinessException.class,() -> confirm(service(ScreeningVerdict.APPROVE,"outbox"),"real-ledger")).code());
    assertEquals(new BigDecimal("100.0000"),wallets.findById(p.sourceWalletId()).orElseThrow().getBalance());
    assertEquals(new BigDecimal("0.0000"),wallets.findById(clearing.getId()).orElseThrow().getBalance());
    assertEquals(2,ledgerCount()); assertEquals(0,operations.count()); assertEquals(0,events.count());
    assertEquals(PaymentLifecycleStatus.QUOTED,current().status());
    assertEquals(PaymentLifecycleStatus.PROCESSING,confirm(service(ScreeningVerdict.APPROVE,null),"real-ledger").status());
    assertEquals(new BigDecimal("90.0000"),wallets.findById(p.sourceWalletId()).orElseThrow().getBalance());
    assertEquals(new BigDecimal("9.0000"),wallets.findById(clearing.getId()).orElseThrow().getBalance());
    assertEquals(new BigDecimal("1.0000"),wallets.findById(fee.getId()).orElseThrow().getBalance());
    var posted=entities.createQuery("select e from LedgerEntry e",LedgerEntry.class).getResultList().stream().filter(e -> e.getIdempotencyKey().startsWith("m3:"+p.id())).toList();
    assertEquals(3,posted.size());
    assertEquals(new BigDecimal("10.0000"),posted.stream().filter(e -> "DEBIT".equals(e.getEntryType())).map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add));
    assertEquals(new BigDecimal("10.0000"),posted.stream().filter(e -> "CREDIT".equals(e.getEntryType())).map(LedgerEntry::getAmount).reduce(BigDecimal.ZERO,BigDecimal::add));
    assertTrue(posted.stream().allMatch(e -> ("m3:"+p.id()).equals(e.getJournalReference())));
    assertEquals(1,events.count()); assertEquals(1,operations.count());
    confirm(service(ScreeningVerdict.APPROVE,null),"real-ledger"); assertEquals(5,ledgerCount());
  }
  long ledgerCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries",Long.class); }
  @Test void concurrentSameKeyClaimHasOnePostingAndLoserReplaysAfterRollback() throws Exception {
    var assessment=observe(M3PaymentFacts.from(current()),ScreeningVerdict.APPROVE);
    var barrier=new CyclicBarrier(2);
    var service=service(ScreeningVerdict.APPROVE,null,facts -> {
      try { barrier.await(5,TimeUnit.SECONDS); return assessment; }
      catch(Exception failure) { throw new IllegalStateException(failure); }
    });
    var workers=Executors.newFixedThreadPool(2);
    try {
      var first=workers.submit(() -> confirm(service,"same")); var second=workers.submit(() -> confirm(service,"same"));
      assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
      assertEquals(1,postings()); assertEquals(1,operations.count()); assertEquals(1,events.count());
    } finally { workers.shutdownNow(); }
  }
  @Test void supersededAssessmentCannotPostOrSaveAnOperation() {
    var facts=M3PaymentFacts.from(current()); var old=observe(facts,ScreeningVerdict.APPROVE);
    var latest=observe(facts,ScreeningVerdict.REVIEW);
    var service=service(ScreeningVerdict.APPROVE,null,ignored -> old);
    assertEquals("STALE_ASSESSMENT",assertThrows(com.fluxpay.config.M5ApiException.class,() -> confirm(service,"stale")).code());
    assertEquals(0,postings()); assertEquals(0,operations.count()); assertEquals(PaymentLifecycleStatus.QUOTED,current().status());
    assertNull(store.byCase(latest.caseId(),false).orElseThrow().paymentDisposition());
  }
  @Test void decisionAwaitingM3DeliveryKeepsItsCaseProtectedFromPublication() {
    confirm(service(ScreeningVerdict.REVIEW,null),"held"); var held=current();
    var snapshot=store.byCase(held.reviewCaseId(),false).orElseThrow().snapshot();
    M5PaymentReader reader=new M5PaymentReader() {
      public UUID ownerOf(UUID id) { return p.senderId(); }
      public M5PaymentSnapshot readForAssessment(UUID id) { return snapshot; }
    };
    var compliance=new M5ComplianceService(store,reader,new M5ComplianceRulesEngine(settings()),manager,clock,new JdbcM5ActiveReviewGuard(jdbc));
    compliance.decide(held.reviewCaseId(),"APPROVE",null,new com.fluxpay.common.security.CurrentUser(UUID.randomUUID(),"synthetic@example.invalid","ADMIN"));
    var next=new M5AssessmentRequest(UUID.randomUUID(),2,p.id(),M5Fingerprints.payment(snapshot));
    assertEquals("ACTIVE_REVIEW",assertThrows(com.fluxpay.config.M5ApiException.class,() -> compliance.assess(next)).code());
    assertEquals(held.reviewCaseId(),store.head(p.id(),false).orElseThrow().latestCaseId());
    var delivery=new M5ReviewDeliveryService(store,new M3M5ReviewDecisionSink(receiver()),manager,clock);
    assertEquals(1,delivery.deliverPending(5)); assertEquals(PaymentLifecycleStatus.DRAFT,current().status());
    assertEquals("ACKNOWLEDGED",store.decisionForCase(held.reviewCaseId()).orElseThrow().deliveryState());
    assertEquals(2,compliance.assess(next).assessmentSequence());
  }
  @Test void independentAssessmentReadsCommittedKycAndFailedAttemptDoesNotCacheAuthorization() {
    jdbc.execute("CREATE TABLE test_kyc (verified NUMBER(1))"); jdbc.update("INSERT INTO test_kyc VALUES (0)");
    M5PaymentReader reader=new M5PaymentReader() {
      public UUID ownerOf(UUID id) { return p.senderId(); }
      public M5PaymentSnapshot readForAssessment(UUID id) {
        var facts=M3PaymentFacts.from(current());
        return new M5PaymentSnapshot(id,facts.senderId(),facts.walletId(),facts.recipientId(),facts.amount(),facts.sourceCurrency(),facts.payoutCurrency(),facts.purpose(),true,facts.recipientSnapshot(),facts.recipientVersion(),"IN",jdbc.queryForObject("SELECT verified FROM test_kyc",Integer.class)==1,true,0L,clock.instant(),clock.instant(),clock.instant().truncatedTo(java.time.temporal.ChronoUnit.DAYS),"UTC","ALL_ATTEMPTS");
      }
    };
    var compliance=new M5ComplianceService(store,reader,new M5ComplianceRulesEngine(settings()),manager,clock,new JdbcM5ActiveReviewGuard(jdbc));
    var bridge=new M3M5ComplianceBridge(compliance,reader,manager);
    var facts=M3PaymentFacts.from(current());
    var observed=tx.execute(s -> {
      jdbc.update("UPDATE test_kyc SET verified=1");
      var assessment=bridge.assess(facts); s.setRollbackOnly(); return assessment;
    });
    assertEquals(ScreeningVerdict.REVIEW,observed.verdict());
    assertFalse(store.byCase(observed.caseId(),false).orElseThrow().snapshot().kycVerified());
    assertEquals(0,jdbc.queryForObject("SELECT verified FROM test_kyc",Integer.class));
    jdbc.update("UPDATE test_kyc SET verified=1");
    var fresh=bridge.assess(facts); assertEquals(ScreeningVerdict.APPROVE,fresh.verdict());
    assertEquals(observed.sequence()+1,fresh.sequence()); assertNotEquals(observed.assessmentId(),fresh.assessmentId());
  }
  com.fluxpay.config.M5ComplianceSettings settings() {
    return new com.fluxpay.config.M5ComplianceSettings(Map.of("USD",new BigDecimal("1000")),ZoneId.of("UTC"),"ALL_ATTEMPTS",Set.of("KP"));
  }
}
