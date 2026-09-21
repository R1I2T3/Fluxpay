package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.service.PaymentRiskComplianceAssessor;
import com.fluxpay.service.UnavailableComplianceAssessor;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ComplianceConfigurationBindingTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(
              VectorConfiguration.class,
              ComplianceConfiguration.class,
              UnavailableComplianceAssessor.class)
          .withBean(ObjectMapper.class, ObjectMapper::new)
          .withBean(PaymentRepository.class, () -> mock(PaymentRepository.class));

  @Test
  void applicationDefaultsBindToTheSharedConfigurationPackages() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(ComplianceAssessor.class);
          assertThat(context.getBean(ComplianceAssessor.class))
              .isInstanceOf(PaymentRiskComplianceAssessor.class);
          assertThat(context.getBean(VectorProperties.class).dimensions()).isEqualTo(1536);
          assertThat(context.getBean(VectorProperties.class).chunkerVersion())
              .isEqualTo("m5-sentence-v1");
          assertThat(context.getBean(CopilotProperties.class).chatModel()).isEqualTo("qwen3:4b");
        });
  }

  @Test
  void legacyPropertyOverridesRemainEffective() {
    runner
        .withPropertyValues(
            "fluxpay.m5.vector.dimensions=768",
            "fluxpay.m5.copilot.chat-model=legacy-model",
            "fluxpay.m5.compliance.review-thresholds.USD=1234")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(VectorProperties.class).dimensions()).isEqualTo(768);
              assertThat(context.getBean(CopilotProperties.class).chatModel())
                  .isEqualTo("legacy-model");
              assertThat(context.getBean(ComplianceProperties.class).reviewThresholds().get("USD"))
                  .isEqualByComparingTo(new BigDecimal("1234"));
            });
  }

  @Test
  void legacyEnvironmentOverridesRemainEffective() {
    runner
        .withPropertyValues(
            "M5_OLLAMA_EMBEDDING_DIMENSIONS=768",
            "M5_OLLAMA_CHAT_MODEL=legacy-env-model",
            "M5_COMPLIANCE_ENABLED=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(VectorProperties.class).dimensions()).isEqualTo(768);
              assertThat(context.getBean(CopilotProperties.class).chatModel())
                  .isEqualTo("legacy-env-model");
              assertThat(context).hasSingleBean(ComplianceAssessor.class);
              assertThat(context.getBean(ComplianceAssessor.class))
                  .isInstanceOf(UnavailableComplianceAssessor.class);
            });
  }

  @Test
  void canonicalEnvironmentOverridesTakePrecedenceOverLegacySettings() {
    runner
        .withPropertyValues(
            "FLUXPAY_OLLAMA_EMBEDDING_DIMENSIONS=1024",
            "M5_OLLAMA_EMBEDDING_DIMENSIONS=768",
            "fluxpay.m5.vector.dimensions=512",
            "FLUXPAY_OLLAMA_CHAT_MODEL=current-model",
            "M5_OLLAMA_CHAT_MODEL=legacy-model",
            "FLUXPAY_COMPLIANCE_ENABLED=false",
            "M5_COMPLIANCE_ENABLED=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(VectorProperties.class).dimensions()).isEqualTo(1024);
              assertThat(context.getBean(CopilotProperties.class).chatModel())
                  .isEqualTo("current-model");
              assertThat(context).hasSingleBean(ComplianceAssessor.class);
              assertThat(context.getBean(ComplianceAssessor.class))
                  .isInstanceOf(UnavailableComplianceAssessor.class);
            });
  }

  @Test
  void canonicalPropertiesBindAndControlComplianceSelection() {
    runner
        .withPropertyValues(
            "fluxpay.vector.dimensions=1024",
            "fluxpay.copilot.chat-model=current-model",
            "fluxpay.compliance.enabled=false",
            "fluxpay.m5.compliance.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(VectorProperties.class).dimensions()).isEqualTo(1024);
              assertThat(context.getBean(CopilotProperties.class).chatModel())
                  .isEqualTo("current-model");
              assertThat(context).hasSingleBean(ComplianceAssessor.class);
              assertThat(context.getBean(ComplianceAssessor.class))
                  .isInstanceOf(UnavailableComplianceAssessor.class);
            });
  }
}
