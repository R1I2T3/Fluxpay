package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fluxpay.adapter.fx.FrankfurterFxProvider;
import com.fluxpay.beans.KycDocumentType;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.FxSnapshotSource;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.development.SimulatedBankNetworkRail;
import com.fluxpay.development.SimulatedComplianceAssessor;
import com.fluxpay.development.SimulatedPartnerNetworkRail;
import com.fluxpay.development.SimulatedRealTimeNetworkRail;
import com.fluxpay.dto.KycFileMeta;
import com.fluxpay.dto.KycSubmitRequest;
import com.fluxpay.exception.DemoFundingDisabledException;
import com.fluxpay.exception.KycException;
import com.fluxpay.repository.ComplianceCaseRepository;
import com.fluxpay.repository.KycCaseRepository;
import com.fluxpay.repository.KycDocumentRepository;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentEventRepository;
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PolicyChunkRepository;
import com.fluxpay.repository.PolicyDocumentRepository;
import com.fluxpay.repository.PolicyGuidanceRepository;
import com.fluxpay.repository.RecipientRepository;
import com.fluxpay.repository.TransferProviderRepository;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import com.fluxpay.repository.TransferRouteRepository;
import com.fluxpay.repository.UserRepository;
import com.fluxpay.repository.WalletOperationRepository;
import com.fluxpay.repository.WalletRepository;
import com.fluxpay.service.DemoFundingService;
import com.fluxpay.service.KycService;
import com.fluxpay.service.PaymentRiskComplianceAssessor;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;

/**
 * Full default-context proof with no real provider credentials: the application boots, FX uses the
 * HTTP adapter, disabled external operations fail honestly, and no default always-approve assessor
 * is active. Development simulators appear only when explicitly enabled.
 */
class DevelopmentDefaultsTest {

  private WebApplicationContextRunner runner(String... properties) {
    String[] defaults = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
      "spring.kafka.listener.auto-startup=false",
      "fluxpay.fx-provider-url=https://fx.invalid/latest",
      "fluxpay.jwt-secret=default-context-test-secret-at-least-thirty-two-bytes",
      "fluxpay.compliance.review-thresholds.USD=10000",
      "fluxpay.vector.ollama-base-url=http://127.0.0.1:11434",
      "fluxpay.vector.embedding-model=qwen3-embedding:4b",
      "fluxpay.vector.dimensions=1536",
      "fluxpay.vector.embedding-space-id=ollama/qwen3-embedding:4b/1536",
      "fluxpay.vector.chunker-version=m5-sentence-v1",
      "fluxpay.copilot.chat-model=qwen3:4b",
      "fluxpay.copilot.chat-temperature=0.2",
      "fluxpay.copilot.max-distance=0.65",
      "fluxpay.copilot.chat-timeout-seconds=90"
    };
    String[] combined = new String[defaults.length + properties.length];
    System.arraycopy(defaults, 0, combined, 0, defaults.length);
    System.arraycopy(properties, 0, combined, defaults.length, properties.length);
    @SuppressWarnings({"rawtypes", "unchecked"})
    WebApplicationContextRunner base =
        new WebApplicationContextRunner()
            .withUserConfiguration(ApplicationComponents.class)
            .withPropertyValues(combined)
            .withBean(
                org.springframework.transaction.PlatformTransactionManager.class,
                () -> mock(org.springframework.transaction.PlatformTransactionManager.class))
            .withBean(
                NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class));
    base =
        base.withBean(
            org.springframework.jdbc.core.JdbcTemplate.class,
            () -> mock(org.springframework.jdbc.core.JdbcTemplate.class));
    for (Class repository :
        new Class<?>[] {
          WalletRepository.class,
          com.fluxpay.repository.BankAccountRepository.class,
          com.fluxpay.repository.WalletTopupRepository.class,
          com.fluxpay.repository.CurrencyConfigurationRepository.class,
          com.fluxpay.repository.LedgerJournalRepository.class,
          com.fluxpay.repository.LedgerJournalLockRepository.class,
          WalletOperationRepository.class,
          UserRepository.class,
          RecipientRepository.class,
          TransferRouteRepository.class,
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
          PolicyChunkRepository.class,
          PolicyGuidanceRepository.class,
          TransferProviderRepository.class,
          TransferRouteOutcomeRepository.class
        }) {
      base = base.withBean(repository, () -> mock(repository));
    }
    return base;
  }

  @Test
  void defaultContextBootsWithHttpFxAndHonestDisabledOperations() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();

              // FX uses the HTTP adapter, never a fake.
              assertThat(context).hasSingleBean(FxSnapshotSource.class);
              assertThat(context.getBean(FxSnapshotSource.class))
                  .isInstanceOf(FrankfurterFxProvider.class);

              // Normal rail discovery excludes simulators unless explicitly enabled.
              assertThat(context.getBeansOfType(TransferRail.class)).isEmpty();
              assertThat(context.getBeanNamesForType(SimulatedBankNetworkRail.class)).isEmpty();
              assertThat(context.getBeanNamesForType(SimulatedRealTimeNetworkRail.class)).isEmpty();
              assertThat(context.getBeanNamesForType(SimulatedPartnerNetworkRail.class)).isEmpty();

              // No default always-approve assessor is active.
              assertThat(context.getBeanNamesForType(SimulatedComplianceAssessor.class)).isEmpty();
              assertThat(context.getBeansOfType(ComplianceAssessor.class)).hasSize(1);
              assertThat(context.getBean(ComplianceAssessor.class))
                  .isInstanceOf(PaymentRiskComplianceAssessor.class);

              // Missing KYC storage fails honestly with 503 before claiming an upload.
              KycService kyc = context.getBean(KycService.class);
              assertThatThrownBy(
                      () ->
                          kyc.submit(
                              UUID.randomUUID(),
                              new KycSubmitRequest(
                                  KycDocumentType.PAN,
                                  "ABCDE1234F",
                                  List.of(new KycFileMeta("pan.pdf", "application/pdf", 1024)))))
                  .isInstanceOfSatisfying(
                      KycException.class,
                      error ->
                          assertThat(error.getCode())
                              .isEqualTo(KycException.KYC_STORAGE_UNAVAILABLE));
              ResponseEntity<com.fluxpay.common.api.ApiError> mapped =
                  new com.fluxpay.web.advice.AuthKycApiExceptionHandler()
                      .handleKyc(
                          new KycException(
                              KycException.KYC_STORAGE_UNAVAILABLE, "No KYC storage."));
              assertThat(mapped.getStatusCode().value()).isEqualTo(503);
              assertThat(mapped.getBody().code()).isEqualTo("KYC_STORAGE_UNAVAILABLE");

              // Development funding is disabled by default and JWT-protected (endpoint requires
              // authentication; the service hides disabled funding before validating the request).
              assertThat(context.getBean(DemoFundingConfig.class).isEnabled()).isFalse();
              assertThatThrownBy(
                      () ->
                          context
                              .getBean(DemoFundingService.class)
                              .receiveDemo(UUID.randomUUID(), null, null))
                  .isInstanceOf(DemoFundingDisabledException.class);
            });
  }

  @Test
  void defaultContextExposesReservationWiringWithoutProviders() {
    runner()
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              com.fluxpay.service.PayoutReservationService reservations =
                  context.getBean(com.fluxpay.service.PayoutReservationService.class);
              assertThat(reservations).isNotNull();
              // Wiring only: the reservation service is present while no provider is discovered.
              // The honest 503-before-reserve behavior is proven in PayoutProviderUnavailableTest.
              com.fluxpay.repository.TransferRouteRepository routes =
                  context.getBean(com.fluxpay.repository.TransferRouteRepository.class);
              assertThat(routes).isNotNull();
            });
  }

  @Test
  void explicitlyEnabledSimulatorsJoinNormalDiscovery() {
    runner(
            "fluxpay.development.simulated-payouts-enabled=true",
            "fluxpay.development.simulated-compliance-enabled=true",
            "fluxpay.development.kyc-metadata-enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBeansOfType(TransferRail.class)).hasSize(3);
              assertThat(context.getBean(ComplianceAssessor.class))
                  .isInstanceOf(SimulatedComplianceAssessor.class);
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
