package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fluxpay.repository.M5ScreeningStore;
import com.fluxpay.service.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

class M5RiskConfigurationUnitTest {
  private ApplicationContextRunner runner() {
    Class<?> config;
    try { config=Class.forName("com.fluxpay.config.M5RiskConfiguration"); }
    catch(ClassNotFoundException absent) { throw new AssertionError("M5 risk Spring wiring is absent",absent); }
    return new ApplicationContextRunner().withUserConfiguration(config,Dependencies.class);
  }
  @Test void riskWiringIsOptInAndDoesNotRegisterVectorServices() {
    runner().run(c -> {
      assertNull(c.getStartupFailure());
      assertEquals(0,c.getBeansOfType(M5AssessmentPort.class).size());
    });
    configured().run(c -> {
      assertNull(c.getStartupFailure());
      assertEquals(1,c.getBeansOfType(M5AssessmentPort.class).size());
      assertEquals(1,c.getBeansOfType(M5ScreeningStore.class).size());
      assertEquals(1,c.getBeansOfType(M5PaymentReader.class).size());
      assertTrue(c.getBeansOfType(M5PolicyService.class).isEmpty());
      assertTrue(c.getBeansOfType(M5CopilotService.class).isEmpty());
      assertTrue(c.getBeansOfType(EmbeddingProvider.class).isEmpty());
      assertTrue(c.getBeansOfType(com.fluxpay.common.contracts.ComplianceAssessor.class).isEmpty());
    });
  }
  @Test void requiredBusinessPolicyCannotSilentlyDefault() {
    runner().withPropertyValues("spring.profiles.active=m5-risk").run(c -> {
      assertNotNull(c.getStartupFailure());
      assertTrue(c.getStartupFailure().toString().contains("m5ComplianceSettings"));
    });
  }
  @Test void loadsExplicitCurrencyAndDayPolicy() {
    configured().run(c -> {
      var policy=c.getBean(M5ComplianceSettings.class);
      assertEquals("1000",policy.highValueThresholds().get("USD").toPlainString());
      assertEquals("Asia/Kolkata",policy.dayZone().getId());
      assertEquals("ALL_ATTEMPTS",policy.recipientTodayMode());
      assertEquals(java.util.Set.of("IR","KP"),policy.highRiskCountries());
    });
  }
  private ApplicationContextRunner configured() {
    return runner().withPropertyValues("spring.profiles.active=m5-risk",
        "compliance.high-value-thresholds.USD=1000","compliance.high-value-thresholds.EUR=920",
        "compliance.high-value-thresholds.INR=83500","compliance.day-zone=Asia/Kolkata",
        "compliance.recipient-today-mode=ALL_ATTEMPTS","compliance.high-risk-countries=IR,KP");
  }
  @Configuration(proxyBeanMethods=false) static class Dependencies {
    @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
    @Bean PlatformTransactionManager transactionManager() { return mock(PlatformTransactionManager.class); }
    @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
  }
}

