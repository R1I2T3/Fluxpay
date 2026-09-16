package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fluxpay.common.contracts.*;
import com.fluxpay.repository.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;

/** Full application component wiring with database access replaced at the repository boundary. */
class ApplicationBoundaryWiringTest {
  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void requiredApplicationContractsHaveOneActiveImplementation() {
    var runner =
        new WebApplicationContextRunner()
            .withUserConfiguration(ApplicationComponents.class)
            .withPropertyValues(
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
                "spring.kafka.listener.auto-startup=false",
                "fluxpay.fx-provider-url=https://fx.invalid/latest",
                "fluxpay.jwt-secret=boundary-test-secret-at-least-thirty-two-bytes",
                "fluxpay.m5.compliance.review-thresholds.USD=10000",
                "fluxpay.m5.vector.ollama-base-url=http://127.0.0.1:11434",
                "fluxpay.m5.vector.embedding-model=qwen3-embedding:4b",
                "fluxpay.m5.vector.dimensions=1536",
                "fluxpay.m5.vector.embedding-space-id=ollama/qwen3-embedding:4b/1536",
                "fluxpay.m5.vector.chunker-version=m5-sentence-v1",
                "fluxpay.m5.copilot.chat-model=qwen3:4b",
                "fluxpay.m5.copilot.chat-temperature=0.2",
                "fluxpay.m5.copilot.max-distance=0.65",
                "fluxpay.m5.copilot.chat-timeout-seconds=90")
            .withBean(
                org.springframework.transaction.PlatformTransactionManager.class,
                () -> mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withBean(
                NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class));
    runner = runner.withBean(org.springframework.jdbc.core.JdbcTemplate.class, () -> mock(org.springframework.jdbc.core.JdbcTemplate.class));
    for (Class repository :
        new Class<?>[] {
          WalletRepository.class,
          WalletOperationRepository.class,
          UserRepository.class,
          RecipientRepository.class,
          PayoutRouteRepository.class,
          PayoutAttemptRepository.class,
          PaymentRepository.class,
          PaymentQuoteRepository.class,
          PaymentOperationRepository.class,
          PaymentEventRepository.class,
          OutboxEventRepository.class,
          OutboxDeliveryRepository.class,
          LedgerEntryRepository.class,
          KycDocumentRepository.class,
          KycCaseRepository.class,
          ComplianceCaseRepository.class,
          PolicyDocumentRepository.class,
          PolicyChunkRepository.class
        }) {
      runner = runner.withBean(repository, () -> mock(repository));
    }
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          for (Class<?> contract :
              new Class<?>[] {
                Clock.class,
                FxRateProvider.class,
                FxSnapshotSource.class,
                WalletPort.class,
                PostingPort.class,
                TransportPort.class,
                LedgerWriter.class,
                WalletProvisioner.class,
                KycGate.class,
                ComplianceAssessor.class,
                PaymentReader.class,
                PaymentEligibilityGate.class,
                RouteAdminAuthorizer.class
              }) {
            assertThat(context.getBeansOfType(contract)).as(contract.getSimpleName()).hasSize(1);
          }
          // Durable outbox path owns delivery; no logging fallback publisher remains.
          assertThat(context.getBeansOfType(TransportPort.class)).hasSize(1);
          assertThat(context.getBeanNamesForType(com.fluxpay.messaging.OutboxService.class))
              .hasSize(1);
          assertThat(context.getBeanNamesForType(com.fluxpay.messaging.OutboxRelay.class))
              .hasSize(1);
          assertThat(context.getBeanNamesForType(com.fluxpay.messaging.OutboxDispatchJob.class))
              .hasSize(1);
        });
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @EnableKafka
  @ComponentScan(
      basePackages = "com.fluxpay",
      excludeFilters = {
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*(Test|IT)(\\$.*)?"),
        @ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = com.fluxpay.FluxPayApplication.class)
      })
  static class ApplicationComponents {}
}
