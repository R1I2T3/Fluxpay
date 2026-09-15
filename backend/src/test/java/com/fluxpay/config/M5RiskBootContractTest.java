package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.contracts.EmbeddingProvider;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.util.UuidRawCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.dto.M5AssessmentRequest;
import com.fluxpay.repository.M5ScreeningStore;
import com.fluxpay.service.M5ComplianceService;
import com.fluxpay.service.M5Fingerprints;
import com.fluxpay.service.M5PaymentReader;
import com.fluxpay.service.M5PolicyService;
import com.fluxpay.service.PaymentService;
import jakarta.servlet.Filter;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Boots the real, explicitly imported risk API against an already prepared synthetic schema. */
class M5RiskBootContractTest {
  @Test
  void assessmentIgnoresCallerKycChangesThatAreLaterRolledBack() {
    var source = preparedSchema();
    var jdbc = new JdbcTemplate(source);
    UUID sender = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    UUID payment = UUID.randomUUID();
    byte[] senderBytes = UuidRawCodec.toBytes(sender);
    // All upstream fixtures are committed before the outer transaction begins.
    jdbc.update("INSERT INTO users(id) VALUES (?)", senderBytes);
    jdbc.update("INSERT INTO wallets VALUES (?, ?, 'USD')", UuidRawCodec.toBytes(wallet), senderBytes);
    jdbc.update("INSERT INTO recipients VALUES (?, ?, 0)", UuidRawCodec.toBytes(recipient), senderBytes);
    jdbc.update("INSERT INTO kyc_cases VALUES (?, ?, 'VERIFIED')",
        UuidRawCodec.toBytes(UUID.randomUUID()), senderBytes);
    jdbc.update("""
        INSERT INTO payments VALUES (?, ?, ?, ?, 100, 'USD', 'INR', 'FAMILY_SUPPORT',
          '{"name":"Recipient","account":"123","bankName":"Bank","country":"IN","currency":"INR"}',
          0, 1, 'DRAFT', TIMESTAMP '2000-01-01 00:00:00')
        """, UuidRawCodec.toBytes(payment), senderBytes, UuidRawCodec.toBytes(wallet),
        UuidRawCodec.toBytes(recipient));

    runner(source, true).run(context -> {
      assertThat(context).hasNotFailed();
      var service = context.getBean(M5ComplianceService.class);
      var reader = context.getBean(M5PaymentReader.class);
      var committed = reader.readForAssessment(payment);
      assertThat(committed.kycVerified()).isTrue();
      var request = new M5AssessmentRequest(UUID.randomUUID(), 1, payment,
          M5Fingerprints.payment(committed));
      var outer = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
      var response = outer.execute(status -> {
        jdbc.update("UPDATE kyc_cases SET status='REJECTED' WHERE user_id=?", senderBytes);
        var assessed = service.assess(request);
        status.setRollbackOnly();
        return assessed;
      });

      assertThat(jdbc.queryForObject("SELECT status FROM kyc_cases WHERE user_id=?", String.class,
          senderBytes)).isEqualTo("VERIFIED");
      var stored = context.getBean(M5ScreeningStore.class).byAssessment(request.assessmentId())
          .orElseThrow();
      assertThat(stored.snapshot().kycVerified()).isTrue();
      assertThat(stored.assessment().reasons()).extracting("code").doesNotContain("KYC_UNVERIFIED");
      assertThat(stored.assessment().risk()).isEqualTo("LOW");
      assertThat(stored.assessment()).isEqualTo(response);
    });
  }

  @Test
  void assessmentHttpRequestPopulatesRiskReasonsAndOwnerPassportFromRealPaymentData() {
    var source = preparedSchema();
    var jdbc = new JdbcTemplate(source);
    UUID sender = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    UUID recipient = UUID.randomUUID();
    UUID payment = UUID.randomUUID();
    byte[] senderBytes = UuidRawCodec.toBytes(sender);
    jdbc.update("INSERT INTO users(id) VALUES (?)", senderBytes);
    jdbc.update("INSERT INTO wallets VALUES (?, ?, 'USD')", UuidRawCodec.toBytes(wallet), senderBytes);
    jdbc.update("INSERT INTO recipients VALUES (?, ?, 0)", UuidRawCodec.toBytes(recipient), senderBytes);
    jdbc.update("INSERT INTO kyc_cases VALUES (?, ?, 'PENDING')", UuidRawCodec.toBytes(UUID.randomUUID()), senderBytes);
    jdbc.update("""
        INSERT INTO payments VALUES (?, ?, ?, ?, 1500, 'USD', 'INR', 'EDUCATION',
          '{"name":"Recipient","account":"123","bankName":"Bank","country":"RU","currency":"INR"}',
          0, 1, 'DRAFT', TIMESTAMP '2000-01-01 00:00:00')
        """, UuidRawCodec.toBytes(payment), senderBytes, UuidRawCodec.toBytes(wallet), UuidRawCodec.toBytes(recipient));

    runner(source, true).run(context -> {
      assertThat(context).hasNotFailed();
      var mvc = MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
      var json = context.getBean(ObjectMapper.class);
      var jwt = context.getBean(JwtUtil.class);
      String adminToken = "Bearer " + jwt.generate(UUID.randomUUID(), "admin@example.invalid", "ADMIN");
      String base = "/api/compliance/payments/" + payment;
      var observation = mvc.perform(get(base + "/assessment-context").header("Authorization", adminToken))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      var startingPoint = json.readTree(observation).path("data");
      String body = json.createObjectNode().put("assessmentId", UUID.randomUUID().toString())
          .put("assessmentSequence", startingPoint.path("nextAssessmentSequence").asLong())
          .put("expectedPaymentFingerprint", startingPoint.path("expectedPaymentFingerprint").asText()).toString();
      var assessed = mvc.perform(post("/api/compliance/assess/" + payment)
              .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isCreated()).andExpect(jsonPath("$.data.risk").value("HIGH"))
          .andExpect(jsonPath("$.data.screeningVerdict").value("REVIEW"))
          .andReturn().getResponse().getContentAsString();
      var originalAssessment = json.readTree(assessed).path("data");
      assertThat(originalAssessment.path("reasons").findValuesAsText("code")).containsExactly(
          "KYC_UNVERIFIED", "FIRST_TO_RECIPIENT", "HIGH_VALUE", "SHORT_PURPOSE", "HIGH_RISK_DEST");
      var stored = jdbc.queryForMap("SELECT risk, risk_reasons FROM screening_cases WHERE payment_id=?",
          UuidRawCodec.toBytes(payment));
      assertThat(stored.get("RISK")).isEqualTo("HIGH");
      String persistedReasons = jdbc.queryForObject("SELECT risk_reasons FROM screening_cases WHERE payment_id=?",
          String.class, UuidRawCodec.toBytes(payment));
      assertThat(json.readTree(persistedReasons)).isEqualTo(originalAssessment.path("reasons"));
      String ownerToken = "Bearer " + jwt.generate(sender, "sender@example.invalid", "USER");
      mvc.perform(get(base + "/passport").header("Authorization", ownerToken))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.risk").value("HIGH"))
          .andExpect(jsonPath("$.data.status").value("UNDER_REVIEW"))
          .andExpect(jsonPath("$.data.caseId").value(originalAssessment.path("caseId").asText()));

      jdbc.update("UPDATE kyc_cases SET status='VERIFIED' WHERE user_id=?", senderBytes);
      var replay = mvc.perform(post("/api/compliance/assess/" + payment)
              .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
      assertThat(json.readTree(replay).path("data")).isEqualTo(originalAssessment);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM screening_cases", Long.class)).isEqualTo(1);
      assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id=?", String.class,
          UuidRawCodec.toBytes(payment))).isEqualTo("DRAFT");
    });
  }

  @Test
  void preparedSchemaBootsSecuredRiskApiWithoutUpstreamOrVectorServices() {
    var source = preparedSchema();
    runner(source, true).run(context -> {
      assertThat(context).hasNotFailed().hasSingleBean(M5ComplianceService.class);
      assertThat(context).doesNotHaveBean(PaymentService.class).doesNotHaveBean(M5PolicyService.class)
          .doesNotHaveBean(EmbeddingProvider.class).doesNotHaveBean(KafkaTemplate.class)
          .doesNotHaveBean(Flyway.class).doesNotHaveBean("entityManagerFactory")
          .doesNotHaveBean("dataSourceScriptDatabaseInitializer");
      var mvc = MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
      mvc.perform(get("/api/compliance/cases")).andExpect(status().isUnauthorized());
      String token = context.getBean(JwtUtil.class)
          .generate(UUID.randomUUID(), "m5-boot-test@example.invalid", "ADMIN");
      mvc.perform(get("/api/compliance/cases").header("Authorization", "Bearer " + token))
          .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
          .andExpect(jsonPath("$.data.totalElements").value(0));
      assertThat(new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM screening_cases", Long.class))
          .isZero();
    });
  }

  @Test
  void missingRequiredM3ColumnAbortsStartupBeforeServingRisk() {
    var source = preparedSchema();
    new JdbcTemplate(source).execute("ALTER TABLE payments DROP COLUMN purpose");
    runner(source, true).run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasStackTraceContaining("M5 risk schema is not ready");
    });
  }

  @Test
  void missingV705TableAbortsStartupWithoutCreatingIt() {
    var source = preparedSchema();
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("DROP TABLE m5_screening_heads");
    runner(source, true).run(context -> {
      assertThat(context).hasFailed();
      assertThat(context.getStartupFailure()).hasStackTraceContaining("M5 risk schema is not ready");
    });
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='M5_SCREENING_HEADS'", Long.class))
        .isZero();
  }

  @Test
  void standaloneConfigurationDoesNotActivateForIntegratedRiskProfileAlone() {
    runner(preparedSchema(), false).run(context -> {
      assertThat(context).hasNotFailed().doesNotHaveBean(M5ComplianceService.class)
          .doesNotHaveBean("m5RiskSchemaPreflight");
    });
  }

  private WebApplicationContextRunner runner(DataSource source, boolean standalone) {
    return new WebApplicationContextRunner().withUserConfiguration(M5RiskApplication.class)
        .withInitializer(context -> context.getEnvironment().setActiveProfiles(standalone
            ? new String[] {"m5-risk", "m5-risk-standalone"} : new String[] {"m5-risk"}))
        .withBean(DataSource.class, () -> source)
        .withPropertyValues("fluxpay.jwt-secret=m5-boot-test-only-key-never-use-in-production-1234567890",
            "compliance.high-value-thresholds.USD=1000", "compliance.day-zone=Asia/Kolkata",
            "compliance.recipient-today-mode=ALL_ATTEMPTS", "compliance.high-risk-countries=RU",
            "spring.flyway.enabled=true");
  }

  private DataSource preparedSchema() {
    var source = new JdbcDataSource();
    source.setURL("jdbc:h2:mem:m5_boot_" + UUID.randomUUID() + ";MODE=Oracle;DB_CLOSE_DELAY=-1");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE TABLE users(id RAW(16) PRIMARY KEY)");
    jdbc.execute("CREATE TABLE wallets(id RAW(16), user_id RAW(16), currency VARCHAR2(3))");
    jdbc.execute("CREATE TABLE recipients(id RAW(16), user_id RAW(16), version NUMBER(10))");
    jdbc.execute("CREATE TABLE kyc_cases(id RAW(16), user_id RAW(16), status VARCHAR2(20))");
    jdbc.execute("""
        CREATE TABLE payments(id RAW(16), sender_id RAW(16), sender_wallet_id RAW(16),
          recipient_id RAW(16), amount NUMBER(19,4), currency VARCHAR2(3), payout_currency VARCHAR2(3),
          purpose VARCHAR2(30), recipient_snapshot CLOB, recipient_version NUMBER(10),
          m3_flow_version NUMBER(1), status VARCHAR2(20), created_at TIMESTAMP)
        """);
    jdbc.execute("""
        CREATE TABLE screening_cases(id RAW(16), payment_id RAW(16), verdict VARCHAR2(20),
          created_at TIMESTAMP, assessment_id RAW(16), assessment_sequence NUMBER(19),
          request_fingerprint VARCHAR2(64), payment_fingerprint VARCHAR2(64), risk VARCHAR2(10),
          status VARCHAR2(20), screening_verdict VARCHAR2(20), risk_reasons CLOB,
          suggested_action VARCHAR2(400), decided_by RAW(16), decided_at TIMESTAMP,
          decision_reason VARCHAR2(500), version NUMBER(10), assessment_snapshot CLOB,
          assessment_response CLOB, rule_version VARCHAR2(100), rule_config_hash VARCHAR2(64),
          review_reference RAW(16), payment_disposition VARCHAR2(20))
        """);
    jdbc.execute("""
        CREATE TABLE m5_screening_heads(payment_id RAW(16), latest_case_id RAW(16),
          latest_sequence NUMBER(19), version NUMBER(10))
        """);
    jdbc.execute("""
        CREATE TABLE m5_review_decisions(id RAW(16), case_id RAW(16), review_reference RAW(16),
          payment_id RAW(16), assessment_id RAW(16), payment_fingerprint VARCHAR2(64),
          decision VARCHAR2(10), reason VARCHAR2(500), reviewer_id RAW(16), decided_at TIMESTAMP,
          delivery_state VARCHAR2(20), retry_count NUMBER(10), next_attempt_at TIMESTAMP,
          last_error_code VARCHAR2(100))
        """);
    return source;
  }
}
