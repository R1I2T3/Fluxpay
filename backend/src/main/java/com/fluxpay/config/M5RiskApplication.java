package com.fluxpay.config;

import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.controller.M5ComplianceController;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicit M5 risk entry point for an already prepared database schema. This application
 * does not scan the shared application, run migrations, or instantiate policy/vector services.
 */
@Configuration(proxyBeanMethods = false)
@Profile("m5-risk-standalone")
@EnableAutoConfiguration(exclude = {HibernateJpaAutoConfiguration.class,
    JpaRepositoriesAutoConfiguration.class, KafkaAutoConfiguration.class,
    FlywayAutoConfiguration.class, SqlInitializationAutoConfiguration.class,
    UserDetailsServiceAutoConfiguration.class})
@Import({M5RiskConfiguration.class, M5ComplianceController.class, M5RiskApiExceptionHandler.class,
    M5SecurityConfig.class, M5RiskSecurityConfig.class, JwtUtil.class, JwtAuthFilter.class,
    CorrelationIdFilter.class})
public class M5RiskApplication {
  private static final List<String> SCHEMA_PROBES = List.of(
      "SELECT id FROM users WHERE 1=0",
      "SELECT id, user_id, currency FROM wallets WHERE 1=0",
      "SELECT id, user_id, version FROM recipients WHERE 1=0",
      "SELECT id, user_id, status FROM kyc_cases WHERE 1=0",
      """
          SELECT id, sender_id, sender_wallet_id, recipient_id, amount, currency,
                 payout_currency, purpose, recipient_snapshot, recipient_version,
                 m3_flow_version, status, created_at
            FROM payments WHERE 1=0
          """,
      """
          SELECT id, payment_id, verdict, created_at, assessment_id, assessment_sequence,
                 request_fingerprint, payment_fingerprint, risk, status, screening_verdict,
                 risk_reasons, suggested_action, decided_by, decided_at, decision_reason,
                 version, assessment_snapshot, assessment_response, rule_version,
                 rule_config_hash, review_reference, payment_disposition
            FROM screening_cases WHERE 1=0
          """,
      """
          SELECT payment_id, latest_case_id, latest_sequence, version
            FROM m5_screening_heads WHERE 1=0
          """,
      """
          SELECT id, case_id, review_reference, payment_id, assessment_id,
                 payment_fingerprint, decision, reason, reviewer_id, decided_at,
                 delivery_state, retry_count, next_attempt_at, last_error_code
            FROM m5_review_decisions WHERE 1=0
          """);

  public static void main(String[] args) {
    var application = new SpringApplication(M5RiskApplication.class);
    application.setAdditionalProfiles("m5-risk", "m5-risk-standalone");
    // A separate config name prevents the shared application's port and infrastructure
    // settings from overriding this entry point. Environment/CLI properties still override.
    application.setDefaultProperties(Map.of(
        "spring.config.name", "m5-risk",
        "server.address", "127.0.0.1",
        "server.port", "8082",
        "spring.datasource.url", "${ORACLE_JDBC_URL}",
        "spring.datasource.username", "${ORACLE_USERNAME}",
        "spring.datasource.password", "${ORACLE_PASSWORD}",
        "spring.datasource.driver-class-name", "oracle.jdbc.OracleDriver",
        "fluxpay.jwt-secret", "${JWT_SECRET}"));
    application.run(args);
  }

  @Bean
  InitializingBean m5RiskSchemaPreflight(JdbcTemplate jdbc) {
    return () -> {
      try {
        for (String probe : SCHEMA_PROBES) jdbc.queryForList(probe);
      } catch (DataAccessException unavailable) {
        throw new IllegalStateException(
            "M5 risk schema is not ready: prepare the required M1/M2/M3 columns and V705 "
                + "screening tables before starting this application. No migrations were run.",
            unavailable);
      }
    };
  }
}
