package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.WalletOperationRepository;
import com.fluxpay.repository.WalletRepository;
import com.fluxpay.service.LedgerJournalService;
import com.fluxpay.service.WalletPostingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BackendBoundaryConfigurationTest {
  @Test
  void canonicalSystemIdentityIsBoundFromApplicationConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(SystemAccountConfig.class)
        .withPropertyValues("fluxpay.system-user-id=11111111-1111-1111-1111-111111111111")
        .run(
            context ->
                assertThat(context.getBean(SystemAccountConfig.class).requireSystemUserId())
                    .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111")));
  }

  @Test
  void malformedSystemIdentityFailsConfigurationExplicitly() {
    new ApplicationContextRunner()
        .withUserConfiguration(SystemAccountConfig.class)
        .withPropertyValues("fluxpay.system-user-id=not-a-user-id")
        .run(
            context ->
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("fluxpay.system-user-id must be a UUID")
                    .isNotNull());
  }

  @Test
  void applicationConfigurationProvidesExactlyOneClock() {
    new ApplicationContextRunner()
        .withUserConfiguration(ClockConfig.class, FxConfig.class)
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withPropertyValues("fluxpay.fx-mode=mock", "fluxpay.fx-provider-url=https://example.test")
        .run(context -> assertThat(context.getBeansOfType(Clock.class)).hasSize(1));
  }

  @Test
  void missingSystemIdentityRejectsDemoPostingAsUnavailable() {
    WalletRepository wallets = mock(WalletRepository.class);
    org.mockito.Mockito.when(wallets.saveAndFlush(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    WalletPostingService posting =
        new WalletPostingService(
            new com.fluxpay.service.SystemAccountService(wallets, new SystemAccountConfig("")),
            wallets,
            mock(WalletOperationRepository.class),
            mock(LedgerJournalService.class),
            new ObjectMapper());

    assertThatThrownBy(
            () ->
                posting.receiveDemo(
                    UUID.randomUUID(), "USD", new BigDecimal("10.0000"), "{}", "receive-key"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("SYSTEM_ACCOUNT_UNAVAILABLE");
            });
  }
}
