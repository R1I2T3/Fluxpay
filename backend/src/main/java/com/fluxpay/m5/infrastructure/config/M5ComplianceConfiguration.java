package com.fluxpay.m5.infrastructure.config;

import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.m5.application.M5AmountComplianceAssessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies M5's concrete compliance implementation to the existing payment-confirmation port. */
@Configuration
@EnableConfigurationProperties(M5ComplianceProperties.class)
@ConditionalOnExpression(
    "'${fluxpay.m5.compliance.enabled:true}' == 'true' && "
        + "'${fluxpay.development.simulated-compliance-enabled:false}' == 'false'")
public class M5ComplianceConfiguration {
  @Bean
  ComplianceAssessor m5ComplianceAssessor(M5ComplianceProperties properties) {
    return new M5AmountComplianceAssessor(properties);
  }
}
