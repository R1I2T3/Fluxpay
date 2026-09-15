package com.fluxpay.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fluxpay.beans.*;
import com.fluxpay.common.security.*;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.config.*;
import com.fluxpay.dto.*;
import com.fluxpay.repository.M5ScreeningStore;
import com.fluxpay.service.*;
import jakarta.servlet.Filter;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Exercises actual HTTP/security/service behavior; only external persistence is replaced. */
class M5RiskControllerWebTest {
  static final UUID PAYMENT=UUID.fromString("50000000-0000-0000-0000-000000000101");
  static final UUID OWNER=UUID.fromString("50000000-0000-0000-0000-000000000102");
  static final UUID ADMIN=UUID.fromString("50000000-0000-0000-0000-000000000103");
  static final Instant NOW=Instant.parse("2026-09-13T10:00:00Z");
  AnnotationConfigWebApplicationContext context;
  MockMvc mvc;
  ObjectMapper json;
  Reader reader;
  MemoryStore store;
  M5ComplianceService service;

  @BeforeEach void start() {
    context=new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.getEnvironment().setActiveProfiles("m5-risk", "local");
    context.register(WebFixture.class);
    try {
      context.register(Class.forName("com.fluxpay.controller.M5ComplianceController"),
          Class.forName("com.fluxpay.config.M5RiskApiExceptionHandler"),
          Class.forName("com.fluxpay.config.M5RiskSecurityConfig"));
    } catch(ClassNotFoundException missing) {
      fail("Authoritative M5 risk HTTP controller and scoped error mapping are not implemented");
    }
    context.refresh();
    json=context.getBean(ObjectMapper.class); reader=context.getBean(Reader.class);
    store=context.getBean(MemoryStore.class); service=context.getBean(M5ComplianceService.class);
    mvc=MockMvcBuilders.webAppContextSetup(context)
        .addFilters(new CorrelationIdFilter(),context.getBean("springSecurityFilterChain",Filter.class)).build();
  }

  @AfterEach void stop() { if(context!=null) context.close(); }

  @Test void signedAdminGetsAuthoritativeContextWithoutCreatingACase() throws Exception {
    var result=mvc.perform(get("/api/compliance/payments/"+PAYMENT+"/assessment-context")
        .header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.paymentId").value(PAYMENT.toString()))
        .andExpect(jsonPath("$.data.nextAssessmentSequence").value(1))
        .andExpect(jsonPath("$.data.observedAt").value(NOW.toString()))
        .andExpect(jsonPath("$.data.recipientSnapshot").doesNotExist()).andReturn();
    String fingerprint=json.readTree(result.getResponse().getContentAsString()).at("/data/expectedPaymentFingerprint").asText();
    assertTrue(fingerprint.matches("[0-9a-f]{64}"));
    assertTrue(store.cases.isEmpty()); assertTrue(store.heads.isEmpty());
    createAssessment();
    mvc.perform(get("/api/compliance/payments/"+PAYMENT+"/assessment-context").header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.nextAssessmentSequence").value(2));
    assertEquals(1,store.cases.size());
  }

  @Test void assessmentPersistsRiskAndReturnsOriginalReplayWithCorrectStatus() throws Exception {
    String body=assessmentBody(UUID.randomUUID(),1);
    var first=mvc.perform(post("/api/compliance/assess/"+PAYMENT).header("Authorization",bearer(ADMIN,"ADMIN"))
        .header("X-Correlation-ID","risk-http-probe").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.correlationId").value("risk-http-probe"))
        .andExpect(jsonPath("$.data.risk").value("HIGH"))
        .andExpect(jsonPath("$.data.reasons[0].code").value("KYC_UNVERIFIED")).andReturn();
    reader.available=false;
    var replay=mvc.perform(post("/api/compliance/assess/"+PAYMENT).header("Authorization",bearer(ADMIN,"ADMIN"))
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn();
    assertEquals(json.readTree(first.getResponse().getContentAsString()).get("data"),
        json.readTree(replay.getResponse().getContentAsString()).get("data"));
    assertEquals(1,store.cases.size()); assertEquals(1,reader.reads);
  }

  @Test void assessmentIdentityConflictsAndUnavailableDataUseDefinedEnvelope() throws Exception {
    UUID id=UUID.randomUUID(); String body=assessmentBody(id,1);
    mvc.perform(post("/api/compliance/assess/"+PAYMENT).header("Authorization",bearer(ADMIN,"ADMIN"))
        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    mvc.perform(post("/api/compliance/assess/"+PAYMENT).header("Authorization",bearer(ADMIN,"ADMIN"))
        .contentType(MediaType.APPLICATION_JSON).content(assessmentBody(id,2)))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSESSMENT_CONFLICT"));
    reader.available=false;
    mvc.perform(get("/api/compliance/payments/"+PAYMENT+"/assessment-context").header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PAYMENT_DATA_UNAVAILABLE"))
        .andExpect(jsonPath("$.fieldErrors").isMap()).andExpect(jsonPath("$.ts").isString())
        .andExpect(jsonPath("$.correlationId").isNotEmpty());
    assertEquals(1,store.cases.size());
  }

  @Test void malformedAssessmentPathAndPaginationFailAsValidationErrors() throws Exception {
    for(String body:List.of("{","{}","{\"assessmentId\":\"not-a-uuid\"}")) {
      mvc.perform(post("/api/compliance/assess/"+PAYMENT).header("Authorization",bearer(ADMIN,"ADMIN"))
          .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }
    for(String route:List.of("/api/compliance/cases/not-a-uuid","/api/compliance/cases?page=word",
        "/api/compliance/cases?page=-1","/api/compliance/cases?size=101","/api/compliance/cases?status=OPEN",
        "/api/compliance/cases?reviewable=banana")) {
      mvc.perform(get(route).header("Authorization",bearer(ADMIN,"ADMIN")))
          .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
    }
    assertTrue(store.cases.isEmpty());
  }

  @Test void adminOnlyAssessmentOperationsRequireSignedIdentity() throws Exception {
    String route="/api/compliance/payments/"+PAYMENT+"/assessment-context";
    mvc.perform(get(route).header("X-Role","ADMIN").header("X-User-Id",ADMIN.toString()))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    mvc.perform(get(route).header("Authorization",bearer(OWNER,"USER")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(post("/api/compliance/assess/"+PAYMENT).header("Authorization",bearer(OWNER,"USER"))
        .contentType(MediaType.APPLICATION_JSON).content(assessmentBody(UUID.randomUUID(),1)))
        .andExpect(status().isForbidden());
    assertEquals(0,reader.reads); assertTrue(store.cases.isEmpty());
  }

  @Test void ownerCanReadStoredPassportAndForeignOwnerCannot() throws Exception {
    JsonNode assessment=createAssessment(); reader.available=false;
    String route="/api/compliance/payments/"+PAYMENT+"/passport";
    mvc.perform(get(route).header("Authorization",bearer(OWNER,"USER")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.caseId").value(assessment.get("caseId").asText()))
        .andExpect(jsonPath("$.data.risk").value("HIGH"));
    mvc.perform(get(route).header("Authorization",bearer(UUID.randomUUID(),"USER")))
        .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CASE_NOT_FOUND"));
    mvc.perform(get(route)).andExpect(status().isUnauthorized());
    assertEquals(1,reader.reads); assertEquals(1,store.cases.size());
  }

  @Test void localProfileHeadersCannotBypassSignedPassportAuthentication() throws Exception {
    createAssessment();
    String route="/api/compliance/payments/"+PAYMENT+"/passport";
    mvc.perform(get(route).header("X-Local-User-Id",OWNER.toString()))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    mvc.perform(get(route).header("X-Local-User-Id",OWNER.toString()).header("Authorization","Bearer invalid"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  @Test void casesHavePaginationAndReviewableFilterWithoutActivatingReview() throws Exception {
    JsonNode assessment=createAssessment();
    mvc.perform(get("/api/compliance/cases?status=UNDER_REVIEW&risk=HIGH&page=0&size=1")
        .header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].risk").value("HIGH"))
        .andExpect(jsonPath("$.data.items[0].reviewable").value(false))
        .andExpect(jsonPath("$.data.totalElements").value(1));
    mvc.perform(get("/api/compliance/cases?reviewable=true").header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty());
    mvc.perform(get("/api/compliance/cases/"+assessment.get("caseId").asText()).header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.screeningVerdict").value("REVIEW"));
  }

  @Test void decisionsRequireActivationAndRejectionReasonAndPersistReviewerIdentity() throws Exception {
    JsonNode assessment=createAssessment(); String id=assessment.get("caseId").asText();
    mvc.perform(put("/api/compliance/cases/"+id+"/approve").header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CASE_CONFLICT"));
    mvc.perform(put("/api/compliance/cases/"+id+"/reject").header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REJECT_REASON_REQUIRED"));
    service.recordDisposition(UUID.fromString(assessment.get("assessmentId").asText()),"REVIEW_REQUIRED",UUID.randomUUID());
    mvc.perform(put("/api/compliance/cases/"+id+"/reject").header("Authorization",bearer(ADMIN,"ADMIN"))
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  Insufficient identity evidence  \",\"decidedBy\":\"untrusted\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"))
        .andExpect(jsonPath("$.data.decidedBy").value(ADMIN.toString()))
        .andExpect(jsonPath("$.data.decisionReason").value("Insufficient identity evidence"))
        .andExpect(jsonPath("$.data.screeningVerdict").value("REVIEW"))
        .andExpect(jsonPath("$.data.deliveryState").value("PENDING"));
    assertEquals(1,store.decisions.size());
  }

  @Test void nullSnapshotAndSequenceExhaustionCannotReturnAnInvalidAssessmentContext() throws Exception {
    reader.snapshot=null;
    mvc.perform(get("/api/compliance/payments/"+PAYMENT+"/assessment-context").header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("PAYMENT_DATA_UNAVAILABLE"));
    reader.snapshot=snapshot();
    store.heads.put(PAYMENT,new M5ScreeningHead(PAYMENT,null,Long.MAX_VALUE,0));
    mvc.perform(get("/api/compliance/payments/"+PAYMENT+"/assessment-context").header("Authorization",bearer(ADMIN,"ADMIN")))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSESSMENT_CONFLICT"));
  }

  private JsonNode createAssessment() throws Exception {
    var result=mvc.perform(post("/api/compliance/assess/"+PAYMENT).header("Authorization",bearer(ADMIN,"ADMIN"))
        .contentType(MediaType.APPLICATION_JSON).content(assessmentBody(UUID.randomUUID(),1)))
        .andExpect(status().isCreated()).andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("data");
  }
  private String assessmentBody(UUID id,long sequence) throws Exception {
    return json.writeValueAsString(Map.of("assessmentId",id,"assessmentSequence",sequence,
        "expectedPaymentFingerprint",M5Fingerprints.payment(snapshot())));
  }
  private String bearer(UUID id,String role) { return "Bearer "+context.getBean(JwtUtil.class).generate(id,"synthetic@example.invalid",role); }
  static M5PaymentSnapshot snapshot() {
    return new M5PaymentSnapshot(PAYMENT,OWNER,UUID.fromString("50000000-0000-0000-0000-000000000104"),
        UUID.fromString("50000000-0000-0000-0000-000000000105"),new BigDecimal("10.00"),"USD","INR",
        "Family support",true,"{\"name\":\"Synthetic recipient\"}",1,"IN",false,true,0L,NOW,NOW,
        Instant.parse("2026-09-12T18:30:00Z"),"Asia/Kolkata","ALL_ATTEMPTS");
  }

  @Configuration @EnableWebMvc @EnableWebSecurity
  @Import({SecurityConfig.class,JwtAuthFilter.class,M5SecurityConfig.class})
  static class WebFixture implements WebMvcConfigurer {
    @Bean ObjectMapper mapper() { return new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS); }
    @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) { converters.add(new MappingJackson2HttpMessageConverter(mapper())); }
    @Bean JwtUtil jwt() { return new JwtUtil("m5-risk-http-synthetic-test-signing-secret-1234567890"); }
    @Bean Reader reader() { return new Reader(); }
    @Bean MemoryStore store() { return new MemoryStore(); }
    @Bean M5ComplianceService compliance() {
      PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
      when(tx.getTransaction(any())).thenAnswer(i->new SimpleTransactionStatus());
      var settings=new M5ComplianceSettings(Map.of("USD",new BigDecimal("1000")),ZoneId.of("Asia/Kolkata"),"ALL_ATTEMPTS",Set.of("KP"));
      return new M5ComplianceService(store(),reader(),new M5ComplianceRulesEngine(settings),tx,Clock.fixed(NOW,ZoneOffset.UTC));
    }
  }

  static class Reader implements M5PaymentReader {
    M5PaymentSnapshot snapshot=snapshot(); boolean available=true; int reads;
    public M5PaymentSnapshot readForAssessment(UUID id) {
      reads++;
      if(!available) throw new M5ApiException(503,"PAYMENT_DATA_UNAVAILABLE","Synthetic unavailable source");
      return snapshot;
    }
    public UUID ownerOf(UUID id) { return OWNER; }
  }
  static class MemoryStore implements M5ScreeningStore {
    final Map<UUID,M5ScreeningCase> cases=new LinkedHashMap<>(); final Map<UUID,M5ScreeningHead> heads=new HashMap<>();
    final Map<UUID,M5ReviewDecision> decisions=new HashMap<>();
    public Optional<M5ScreeningCase> byAssessment(UUID id){return cases.values().stream().filter(c->c.assessment().assessmentId().equals(id)).findFirst();}
    public Optional<M5ScreeningCase> byCase(UUID id,boolean lock){return Optional.ofNullable(cases.get(id));}
    public Optional<M5ScreeningHead> head(UUID id,boolean lock){return Optional.ofNullable(heads.get(id));}
    public void createHead(UUID id){heads.put(id,new M5ScreeningHead(id,null,0,0));}
    public void insertCase(M5ScreeningCase c){cases.put(c.assessment().caseId(),c);}
    public void updateCase(M5ScreeningCase c){insertCase(c);}
    public void publishHead(M5ScreeningHead h){heads.put(h.paymentId(),h);}
    public Optional<M5ReviewDecision> decisionForCase(UUID id){return decisions.values().stream().filter(d->d.command().caseId().equals(id)).findFirst();}
    public Optional<M5ReviewDecision> decision(UUID id,boolean lock){return Optional.ofNullable(decisions.get(id));}
    public void insertDecision(M5ReviewDecision d){decisions.put(d.command().decisionId(),d);}
    public void updateDelivery(M5ReviewDecision d){insertDecision(d);}
    public List<M5ReviewDecision> pending(Instant now,int limit){return List.of();}
    private boolean eligible(M5ScreeningCase c,String status,String risk,Boolean reviewable) {
      boolean active="UNDER_REVIEW".equals(c.status()) && "REVIEW_REQUIRED".equals(c.paymentDisposition())
          && c.assessment().caseId().equals(heads.get(c.assessment().paymentId()).latestCaseId());
      return (status==null||status.equals(c.status())) && (risk==null||risk.equals(c.assessment().risk())) && (reviewable==null||reviewable==active);
    }
    public List<M5ScreeningCase> list(String status,String risk,Boolean reviewable,int page,int size){return cases.values().stream().filter(c->eligible(c,status,risk,reviewable)).skip((long)page*size).limit(size).toList();}
    public long count(String status,String risk,Boolean reviewable){return cases.values().stream().filter(c->eligible(c,status,risk,reviewable)).count();}
  }
}
