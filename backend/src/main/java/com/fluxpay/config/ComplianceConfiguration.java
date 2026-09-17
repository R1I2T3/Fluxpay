package com.fluxpay.config;

import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.service.AmountComplianceAssessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies the concrete compliance implementation to the existing payment-confirmation port. */
@Configuration
@EnableConfigurationProperties(ComplianceProperties.class)
@ConditionalOnExpression(
    "'${fluxpay.compliance.enabled:true}' == 'true' && "
        + "'${fluxpay.development.simulated-compliance-enabled:false}' == 'false'")
public class ComplianceConfiguration {
  @Bean
  ComplianceAssessor complianceAssessor(ComplianceProperties properties) {
    return new AmountComplianceAssessor(properties);
  }
}
