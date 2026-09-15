package com.fluxpay.config;

import com.fluxpay.common.security.*;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.controller.*;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

/** M5 only unless m5-m3-integration is explicitly enabled. No schema mutations at boot. */
@Profile("m5-backend") @Configuration(proxyBeanMethods=false)
@EnableAutoConfiguration(exclude={HibernateJpaAutoConfiguration.class,JpaRepositoriesAutoConfiguration.class,
    FlywayAutoConfiguration.class,KafkaAutoConfiguration.class,SqlInitializationAutoConfiguration.class,
    UserDetailsServiceAutoConfiguration.class})
@Import({M5RiskConfiguration.class,M5VectorConfiguration.class,M5RiskSecurityConfig.class,
    M5BackendSecurityConfig.class,M5RiskApiExceptionHandler.class,M5ComplianceController.class,
    M5PolicyController.class,M5CopilotController.class,JwtUtil.class,JwtAuthFilter.class,
    CorrelationIdFilter.class,M5M3IntegrationConfiguration.class})
public class M5BackendApplication {
  public static void main(String[] args) {
    var app=new SpringApplication(M5BackendApplication.class);
    app.setAdditionalProfiles("m5-risk","m5-backend");
    app.setDefaultProperties(Map.ofEntries(
        Map.entry("spring.config.name","m5-backend"),Map.entry("server.address","127.0.0.1"),Map.entry("server.port","8082"),
        Map.entry("spring.datasource.url","${ORACLE_JDBC_URL}"),Map.entry("spring.datasource.username","${ORACLE_USERNAME}"),
        Map.entry("spring.datasource.password","${ORACLE_PASSWORD}"),Map.entry("spring.datasource.driver-class-name","oracle.jdbc.OracleDriver"),
        Map.entry("spring.datasource.hikari.connection-timeout","3000"),Map.entry("spring.datasource.hikari.validation-timeout","1000"),
        Map.entry("spring.datasource.hikari.connection-init-sql","ALTER SESSION SET TIME_ZONE='UTC'"),
        Map.entry("spring.datasource.hikari.maximum-pool-size","10"),Map.entry("spring.jpa.open-in-view","false"),
        Map.entry("spring.jpa.hibernate.ddl-auto","none"),Map.entry("spring.jpa.properties.hibernate.jdbc.time_zone","UTC"),
        Map.entry("fluxpay.jwt-secret","${JWT_SECRET}")));
    app.run(args);
  }
  @Bean static BeanFactoryPostProcessor m5BackendRejectFixtureProfiles(Environment environment) {
    return factory -> {
      if(environment.acceptsProfiles(Profiles.of("local","m5-solo","m5-legacy")))
        throw new IllegalStateException("The local profile is not permitted in the authoritative M5 backend; fixture/legacy substitutions are disabled");
      if(environment.acceptsProfiles(Profiles.of("m5-m3-integration"))
          && environment.getProperty("spring.jpa.open-in-view",Boolean.class,false))
        throw new IllegalStateException("spring.jpa.open-in-view must be false for fresh locked payment state after screening");
    };
  }
  @Bean FilterRegistrationBean<JwtAuthFilter> m5DisableGlobalJwtRegistration(JwtAuthFilter jwt) {
    var registration=new FilterRegistrationBean<>(jwt);registration.setEnabled(false);return registration;
  }
  @Bean InitializingBean m5BackendSchemaPreflight(JdbcTemplate jdbc) {
    return () -> {
      try {
        jdbc.queryForList("SELECT assessment_id,payment_fingerprint,payment_disposition FROM screening_cases WHERE 1=0");
        jdbc.queryForList("SELECT latest_case_id FROM m5_screening_heads WHERE 1=0");
        jdbc.queryForList("SELECT delivery_state FROM m5_review_decisions WHERE 1=0");
        jdbc.queryForList("SELECT canonical_hash,active_generation_id,superseded_by_id FROM policy_documents WHERE 1=0");
        jdbc.queryForList("SELECT generation_id,embedding FROM policy_chunks WHERE 1=0");
        jdbc.queryForList("SELECT embedding_space_id FROM policy_generations WHERE 1=0");
      } catch(org.springframework.dao.DataAccessException missing) {
        throw new IllegalStateException("M5 backend schema is not ready; apply migrations through V708 explicitly. No migrations were run at startup.",missing);
      }
    };
  }
}
