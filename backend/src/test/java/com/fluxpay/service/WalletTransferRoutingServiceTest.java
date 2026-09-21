package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.adapter.transfer.InternalLedgerTransferRail;
import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.beans.User;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.config.ConversionFeeSchedule;
import com.fluxpay.development.SimulatedBankNetworkRail;
import com.fluxpay.development.SimulatedPartnerNetworkRail;
import com.fluxpay.development.SimulatedRealTimeNetworkRail;
import com.fluxpay.domain.ConversionMath;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RouteOutcome;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RankedRouteQuote;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.dto.WalletTransferRequest;
import com.fluxpay.dto.WalletTransferResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferRouteRepository;
import com.fluxpay.repository.UserRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(
    showSql = false,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:task9;MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = WalletTransferRoutingServiceTest.Config.class)
@Import({
  WalletTransferRoutingService.class,
  WalletOperationService.class,
  ConversionMath.class,
  ConversionFeeSchedule.class,
  FxQuoteValidator.class,
  WalletRequestNormalizer.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WalletTransferRoutingServiceTest {
  static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories("com.fluxpay.repository")
  static class Config {
    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    Clock clock() {
      return Clock.fixed(NOW, java.time.ZoneOffset.UTC);
    }

    @Bean
    InternalLedgerTransferRail internalRail() {
      InternalLedgerTransferRail rail = mock(InternalLedgerTransferRail.class);
      lenient().when(rail.type()).thenReturn(RailType.INTERNAL_LEDGER);
      lenient()
          .when(rail.supportedDestinations())
          .thenReturn(Set.of(DestinationType.INTERNAL_WALLET));
      return rail;
    }

    @Bean
    RailRegistry rails(InternalLedgerTransferRail internalRail) {
      return new RailRegistry(List.of(internalRail));
    }
  }

  @Autowired WalletTransferRoutingService service;
  @Autowired InternalLedgerTransferRail internalRail;
  @Autowired UserRepository users;
  @Autowired WalletRepository wallets;

  @MockBean SmartRoutingService routing;
  @MockBean RouteOutcomeRecorder outcomes;
  @MockBean FxQuoteService quotes;
  @MockBean CurrencyScaleService scales;

  // Fresh identities per test: this slice is non-transactional, so rows persist across methods
  // and fixed ids would break single-result repository queries.
  UUID USER_ID;
  UUID RECIPIENT_ID;
  UUID INTERNAL_ROUTE_ID;
  UUID INTERNAL_PROVIDER_ID;
  WalletTransferRequest request;

  @BeforeEach
  void setUp() {
    USER_ID = UUID.randomUUID();
    RECIPIENT_ID = UUID.randomUUID();
    INTERNAL_ROUTE_ID = UUID.randomUUID();
    INTERNAL_PROVIDER_ID = UUID.randomUUID();
    // The rail is a shared @Bean mock (stubbed at construction for registry startup), so unlike
    // @MockBean it is not reset between tests: clear invocations while keeping its stubs.
    clearInvocations(internalRail);
    request = new WalletTransferRequest(RECIPIENT_ID, null, "USD", "USD", "20", "SOURCE", null);
    when(scales.scale("USD")).thenReturn(2);
    when(scales.scale("EUR")).thenReturn(2);
    when(scales.scale("INR")).thenReturn(2);
    users.saveAndFlush(new User(USER_ID, USER_ID + "@test.invalid", "!", "USER", "Test", NOW, NOW));
    users.saveAndFlush(
        new User(RECIPIENT_ID, RECIPIENT_ID + "@test.invalid", "!", "USER", "Test", NOW, NOW));
    customer(USER_ID, "USD", "20000");
    customer(RECIPIENT_ID, "USD", "0");
  }

  @Test
  void p2pUsesBalancedInternalWinnerAndRecordsOutcome() {
    when(routing.recommend(any(), eq(RoutePreference.BALANCED)))
        .thenReturn(recommendation(internalRoute("FLUXPAY_INR_INTERNAL")));
    when(internalRail.execute(any()))
        .thenReturn(TransferRailResult.completed("wallet:p2p:op-1", BigDecimal.ZERO));

    WalletTransferResponse response = service.transfer(USER_ID, request, "wallet-key");

    assertThat(response.routeCode()).isEqualTo("FLUXPAY_INR_INTERNAL");
    assertThat(response.providerCode()).isEqualTo("FLUXPAY");
    assertThat(response.railType()).isEqualTo(RailType.INTERNAL_LEDGER);
    verify(outcomes).record(INTERNAL_ROUTE_ID, "wallet:p2p:op-1", RouteOutcome.COMPLETED);
  }

  @Test
  void noEligibleInternalRouteFailsWithoutExecution() {
    when(routing.recommend(any(), eq(RoutePreference.BALANCED)))
        .thenThrow(
            new BusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "NO_ELIGIBLE_ROUTES",
                "No transfer route is eligible for this destination."));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.transfer(USER_ID, request, "no-route"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("NO_ELIGIBLE_ROUTES"));
    verify(internalRail, org.mockito.Mockito.never()).execute(any());
    verifyNoInteractions(outcomes);
  }

  @Test
  void externalWinnerIsRejectedBeforeExecution() {
    when(routing.recommend(any(), eq(RoutePreference.BALANCED)))
        .thenReturn(recommendation(externalRoute()));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.transfer(USER_ID, request, "external-route"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            e -> {
              assertThat(e.code()).isEqualTo("INVALID_TRANSFER_ROUTE");
              assertThat(e.status().value()).isEqualTo(400);
            });
    verify(internalRail, org.mockito.Mockito.never()).execute(any());
    verifyNoInteractions(outcomes);
  }

  @Test
  void failedRailResultRecordsFailedAndThrows() {
    when(routing.recommend(any(), eq(RoutePreference.BALANCED)))
        .thenReturn(recommendation(internalRoute("FLUXPAY_INR_INTERNAL")));
    when(internalRail.execute(any()))
        .thenReturn(
            TransferRailResult.failed("LEDGER_REJECTED", "ledger rejected", BigDecimal.ZERO));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.transfer(USER_ID, request, "failed-posting"))
        .isInstanceOf(BusinessException.class);
    verify(outcomes)
        .record(
            eq(INTERNAL_ROUTE_ID),
            argThat(ref -> ref != null && ref.startsWith("wallet:p2p:")),
            eq(RouteOutcome.FAILED));
  }

  @Test
  void uncertainRailResultThrowsWithoutRecording() {
    when(routing.recommend(any(), eq(RoutePreference.BALANCED)))
        .thenReturn(recommendation(internalRoute("FLUXPAY_INR_INTERNAL")));
    when(internalRail.execute(any()))
        .thenReturn(TransferRailResult.uncertain("PROVIDER_TIMEOUT", "timed out", BigDecimal.ZERO));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.transfer(USER_ID, request, "uncertain-posting"))
        .isInstanceOf(IllegalStateException.class);
    verifyNoInteractions(outcomes);
  }

  @Test
  void allInstalledRailTypesResolveCompatibleSoTheSkipPathIsDead() {
    InternalLedgerTransferRail ledger =
        new InternalLedgerTransferRail(
            mock(WalletPostingService.class),
            mock(TransferRouteRepository.class),
            mock(RouteReliabilityService.class),
            Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    RailRegistry registry =
        new RailRegistry(
            List.of(
                new SimulatedBankNetworkRail(),
                new SimulatedRealTimeNetworkRail(),
                new SimulatedPartnerNetworkRail(),
                ledger));

    assertThat(
            registry.requireCompatible(RailType.INTERNAL_LEDGER, DestinationType.INTERNAL_WALLET))
        .isSameAs(ledger);
    assertThat(registry.requireCompatible(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT))
        .isInstanceOf(TransferRail.class);
    assertThat(
            registry.requireCompatible(
                RailType.REAL_TIME_NETWORK, DestinationType.EXTERNAL_ACCOUNT))
        .isInstanceOf(TransferRail.class);
    assertThat(
            registry.requireCompatible(RailType.PARTNER_NETWORK, DestinationType.EXTERNAL_ACCOUNT))
        .isInstanceOf(TransferRail.class);
    assertThatNoException()
        .isThrownBy(
            () -> {
              RoutingCompatibility.requireCompatibleIfInstalled(
                  registry, RailType.INTERNAL_LEDGER, DestinationType.INTERNAL_WALLET);
              RoutingCompatibility.requireCompatibleIfInstalled(
                  registry, RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT);
              RoutingCompatibility.requireCompatibleIfInstalled(
                  registry, RailType.REAL_TIME_NETWORK, DestinationType.EXTERNAL_ACCOUNT);
              RoutingCompatibility.requireCompatibleIfInstalled(
                  registry, RailType.PARTNER_NETWORK, DestinationType.EXTERNAL_ACCOUNT);
            });
  }

  TransferRoute internalRoute(String code) {
    TransferProvider provider =
        TransferProvider.create(
            INTERNAL_PROVIDER_ID, "FLUXPAY", "FluxPay", RailType.INTERNAL_LEDGER, true, false, NOW);
    return TransferRoute.create(
        INTERNAL_ROUTE_ID,
        provider,
        code,
        code + " name",
        DestinationType.INTERNAL_WALLET,
        null,
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        5,
        new BigDecimal("100.00"),
        null,
        null,
        true,
        false,
        NOW);
  }

  TransferRoute externalRoute() {
    TransferProvider provider =
        TransferProvider.create(
            UUID.randomUUID(), "HDFC_BANK", "HDFC Bank", RailType.BANK_NETWORK, true, false, NOW);
    return TransferRoute.create(
        UUID.randomUUID(),
        provider,
        "HDFC_INR_STANDARD",
        "HDFC INR Standard",
        DestinationType.EXTERNAL_ACCOUNT,
        "IN",
        "INR",
        new BigDecimal("5.0000"),
        new BigDecimal("0.500000"),
        60,
        new BigDecimal("99.00"),
        null,
        null,
        true,
        false,
        NOW);
  }

  static RouteRecommendation recommendation(TransferRoute route) {
    RouteQuote quote =
        new RouteQuote(
            route,
            BigDecimal.ONE,
            BigDecimal.ONE,
            new BigDecimal("20.0000"),
            BigDecimal.ZERO,
            new BigDecimal("20.0000"),
            new BigDecimal("100.000000"));
    RankedRouteQuote ranked = new RankedRouteQuote(quote, BigDecimal.ONE, 1);
    return new RouteRecommendation(ranked, List.of(ranked), "balanced test winner");
  }

  private void customer(UUID user, String currency, String amount) {
    Wallet wallet = new Wallet(user, currency, WalletAccountRole.CUSTOMER);
    wallet.setBalance(new BigDecimal(amount).setScale(4));
    wallets.saveAndFlush(wallet);
  }
}
