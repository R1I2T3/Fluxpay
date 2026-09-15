package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.controller.*;
import com.fluxpay.service.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PayoutProfileTest {
  @ParameterizedTest
  @ValueSource(strings = {"local", "integration"})
  void nonMockProfilesDoNotLoadComponentsRequiringMockContracts(String profile) {
    new ApplicationContextRunner()
        .withPropertyValues("spring.profiles.active=" + profile)
        .withUserConfiguration(
            PayoutController.class,
            RouteController.class,
            RouteAdminController.class,
            TimelineController.class,
            RouteCatalogService.class,
            PayoutExecutionService.class,
            RecoveryService.class,
            RefundJournalService.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context).doesNotHaveBean(PayoutController.class);
              assertThat(context).doesNotHaveBean(RecoveryService.class);
            });
  }
}
