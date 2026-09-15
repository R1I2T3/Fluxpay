package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.repository.*;
import com.fluxpay.service.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Opt-in risk wiring; policy/vector beans and shared assessor contracts are untouched. */
@Configuration(proxyBeanMethods=false)
@Profile("m5-risk")
public class M5RiskConfiguration {
  @Bean M5ComplianceSettings m5ComplianceSettings(Environment environment) {
    Binder binder=Binder.get(environment);
    Map<String,BigDecimal> thresholds=binder.bind("compliance.high-value-thresholds",
        Bindable.mapOf(String.class,BigDecimal.class)).orElseThrow(
            () -> new IllegalArgumentException("Explicit compliance.high-value-thresholds are required"));
    String zone=environment.getRequiredProperty("compliance.day-zone");
    String mode=environment.getRequiredProperty("compliance.recipient-today-mode");
    Set<String> countries=binder.bind("compliance.high-risk-countries",
        Bindable.setOf(String.class)).orElseThrow(
            () -> new IllegalArgumentException("Explicit compliance.high-risk-countries are required"));
    return new M5ComplianceSettings(thresholds,ZoneId.of(zone),mode,countries);
  }
  @Bean M5PaymentObservationRepository m5PaymentObservationRepository(JdbcTemplate jdbc) {
    return new M5PaymentObservationRepository(jdbc);
  }
  @Bean M5PaymentReader m5PaymentReader(M5PaymentObservationRepository observations,
      ObjectMapper json,M5ComplianceSettings settings) {
    return new DatabaseM5PaymentReader(observations,json,settings,Clock.systemUTC());
  }
  @Bean M5ScreeningStore m5ScreeningStore(JdbcTemplate jdbc,ObjectMapper json) {
    return new JdbcM5ScreeningStore(jdbc,json);
  }
  @Bean M5ComplianceRulesEngine m5ComplianceRulesEngine(M5ComplianceSettings settings) {
    return new M5ComplianceRulesEngine(settings);
  }
  @Bean M5ComplianceService m5ComplianceService(M5ScreeningStore store,M5PaymentReader reader,
      M5ComplianceRulesEngine rules,PlatformTransactionManager transactions,
      org.springframework.beans.factory.ObjectProvider<M5ActiveReviewGuard> activeReviews) {
    return new M5ComplianceService(store,reader,rules,transactions,Clock.systemUTC(),
        activeReviews.getIfAvailable(() -> (payment,caseId) -> false));
  }
}
