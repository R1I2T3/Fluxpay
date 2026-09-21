package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.DeletionResult;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferProviderRepository;
import com.fluxpay.repository.TransferRouteRepository;
import com.fluxpay.service.TransferRouteService.CreateRoute;
import com.fluxpay.service.TransferRouteService.UpdateRoute;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TransferRouteServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
  private static final UUID PROVIDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_PROVIDER_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");
  private static final UUID ROUTE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock private TransferRouteRepository routes;
  @Mock private TransferProviderRepository providers;
  @Mock private RoutingUsageService usage;

  private TransferRouteService service;
  private TransferProvider provider;
  private TransferRoute route;

  @BeforeEach
  void setUp() {
    RailRegistry rails =
        new RailRegistry(
            List.of(
                fake(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT),
                fake(RailType.INTERNAL_LEDGER, DestinationType.INTERNAL_WALLET)));
    service =
        new TransferRouteService(routes, providers, usage, rails, Clock.fixed(NOW, ZoneOffset.UTC));
    provider = TransferRouteTestFixtures.provider(PROVIDER_ID, NOW);
    route = TransferRouteTestFixtures.externalRoute(ROUTE_ID, provider, NOW);
  }

  @Test
  void createNormalizesCodeAndPersistsRoute() {
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(routes.findByRouteCode("HDFC_INR_STANDARD")).thenReturn(Optional.empty());
    when(routes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    TransferRoute created = service.create(createCommand("hdfc_inr_standard", true));

    assertThat(created.code()).isEqualTo("HDFC_INR_STANDARD");
    assertThat(created.destinationCountry()).isEqualTo("IN");
    assertThat(created.payoutCurrency()).isEqualTo("INR");
    assertThat(created.systemProtected()).isFalse();
  }

  @Test
  void createRejectsDuplicateCode() {
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(routes.findByRouteCode("HDFC_INR_STANDARD")).thenReturn(Optional.of(route));

    assertThatThrownBy(() -> service.create(createCommand("HDFC_INR_STANDARD", true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("ROUTE_CODE_CONFLICT");
              assertThat(error.status().value()).isEqualTo(409);
            });
  }

  @Test
  void createRejectsUnknownProvider() {
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(createCommand("HDFC_INR_STANDARD", true)))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.status().value()).isEqualTo(404));
  }

  @Test
  void createRejectsIncompatibleRail() {
    TransferProvider ledger =
        TransferProvider.create(
            PROVIDER_ID, "FLUXPAY", "FluxPay", RailType.INTERNAL_LEDGER, true, false, NOW);
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(ledger));
    when(routes.findByRouteCode("HDFC_INR_STANDARD")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(createCommand("HDFC_INR_STANDARD", true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE");
              assertThat(error.status().value()).isEqualTo(400);
            });
  }

  @Test
  void createRejectsActiveRouteUnderInactiveProvider() {
    TransferProvider inactive =
        TransferProvider.create(
            PROVIDER_ID, "HDFC_BANK", "HDFC Bank", RailType.BANK_NETWORK, false, false, NOW);
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(inactive));
    when(routes.findByRouteCode("HDFC_INR_STANDARD")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(createCommand("HDFC_INR_STANDARD", true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
  }

  @Test
  void createActiveRouteRejectsUninstalledProviderRail() {
    TransferProvider partner =
        TransferProvider.create(
            PROVIDER_ID, "PARTNER", "Partner", RailType.PARTNER_NETWORK, true, false, NOW);
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(partner));
    when(routes.findByRouteCode("HDFC_INR_STANDARD")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(createCommand("HDFC_INR_STANDARD", true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("TRANSFER_RAIL_UNAVAILABLE");
              assertThat(error.status().value()).isEqualTo(503);
            });
  }

  @Test
  void createInactiveRouteAllowsUninstalledProviderRail() {
    TransferProvider partner =
        TransferProvider.create(
            PROVIDER_ID, "PARTNER", "Partner", RailType.PARTNER_NETWORK, true, false, NOW);
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(partner));
    when(routes.findByRouteCode("HDFC_INR_STANDARD")).thenReturn(Optional.empty());
    when(routes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    TransferRoute created = service.create(createCommand("HDFC_INR_STANDARD", false));

    assertThat(created.active()).isFalse();
    assertThat(created.provider().railType()).isEqualTo(RailType.PARTNER_NETWORK);
  }

  @Test
  void createRejectsInvalidCorridorAndLimits() {
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(routes.findByRouteCode("HDFC_INR_STANDARD")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateRoute(
                        PROVIDER_ID,
                        "HDFC_INR_STANDARD",
                        "HDFC INR Standard",
                        DestinationType.EXTERNAL_ACCOUNT,
                        null,
                        "INR",
                        new BigDecimal("5.0000"),
                        new BigDecimal("0.500000"),
                        60,
                        new BigDecimal("99.00"),
                        null,
                        null,
                        true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateRoute(
                        PROVIDER_ID,
                        "HDFC_INR_STANDARD",
                        "HDFC INR Standard",
                        DestinationType.EXTERNAL_ACCOUNT,
                        "IN",
                        "INR",
                        new BigDecimal("5.0000"),
                        new BigDecimal("0.500000"),
                        60,
                        new BigDecimal("99.00"),
                        new BigDecimal("500000.0000"),
                        new BigDecimal("1.0000"),
                        true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));

    assertThatThrownBy(
            () ->
                service.create(
                    new CreateRoute(
                        PROVIDER_ID,
                        "HDFC_INR_STANDARD",
                        "HDFC INR Standard",
                        DestinationType.EXTERNAL_ACCOUNT,
                        "IN",
                        "INR",
                        new BigDecimal("5.0000"),
                        new BigDecimal("0.500000"),
                        0,
                        new BigDecimal("99.00"),
                        null,
                        null,
                        true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
  }

  @Test
  void updateRejectsProviderChangeAfterUse() {
    TransferProvider other = TransferRouteTestFixtures.provider(OTHER_PROVIDER_ID, NOW);
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(usage.routeUsed(ROUTE_ID)).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.update(
                    ROUTE_ID,
                    updateCommand(other.id(), DestinationType.EXTERNAL_ACCOUNT, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("ROUTING_BINDING_IMMUTABLE"));
  }

  @Test
  void updateRejectsDestinationChangeAfterUse() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(usage.routeUsed(ROUTE_ID)).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.update(
                    ROUTE_ID,
                    updateCommand(PROVIDER_ID, DestinationType.INTERNAL_WALLET, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("ROUTING_BINDING_IMMUTABLE"));
  }

  @Test
  void updateAllowsBindingChangeBeforeUse() {
    TransferProvider other =
        TransferProvider.create(
            OTHER_PROVIDER_ID, "SBI_BANK", "SBI", RailType.BANK_NETWORK, true, false, NOW);
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(usage.routeUsed(ROUTE_ID)).thenReturn(false);
    when(providers.findByIdForUpdate(OTHER_PROVIDER_ID)).thenReturn(Optional.of(other));

    TransferRoute updated =
        service.update(
            ROUTE_ID, updateCommand(OTHER_PROVIDER_ID, DestinationType.EXTERNAL_ACCOUNT, true, 0L));

    assertThat(updated.provider().id()).isEqualTo(OTHER_PROVIDER_ID);
    verify(routes).flush();
  }

  @Test
  void updateAllowsCommercialEdits() {
    // Binding fields are unchanged, so usage is not consulted and commercials stay editable.
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(provider));

    TransferRoute updated =
        service.update(
            ROUTE_ID,
            new UpdateRoute(
                PROVIDER_ID,
                "HDFC INR Standard",
                DestinationType.EXTERNAL_ACCOUNT,
                "IN",
                "INR",
                new BigDecimal("7.5000"),
                new BigDecimal("0.750000"),
                30,
                new BigDecimal("98.50"),
                new BigDecimal("1.0000"),
                new BigDecimal("500000.0000"),
                true,
                0L));

    assertThat(updated.baseFee()).isEqualByComparingTo("7.5000");
    assertThat(updated.estimatedMinutes()).isEqualTo(30);
    verify(routes).flush();
  }

  @Test
  void updateRejectsIncompatibleDestination() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(usage.routeUsed(ROUTE_ID)).thenReturn(false);
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(provider));

    assertThatThrownBy(
            () ->
                service.update(
                    ROUTE_ID,
                    updateCommand(PROVIDER_ID, DestinationType.INTERNAL_WALLET, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
  }

  @Test
  void updateRejectsActivationUnderInactiveProvider() {
    TransferProvider inactive =
        TransferProvider.create(
            PROVIDER_ID, "HDFC_BANK", "HDFC Bank", RailType.BANK_NETWORK, false, false, NOW);
    TransferRoute dormant =
        TransferRouteTestFixtures.inactiveExternalRoute(ROUTE_ID, inactive, NOW);
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(dormant));
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(inactive));

    assertThatThrownBy(
            () ->
                service.update(
                    ROUTE_ID,
                    updateCommand(PROVIDER_ID, DestinationType.EXTERNAL_ACCOUNT, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
  }

  @Test
  void updateRejectsActivationWhenProviderRailIsUninstalled() {
    TransferProvider partner =
        TransferProvider.create(
            PROVIDER_ID, "PARTNER", "Partner", RailType.PARTNER_NETWORK, true, false, NOW);
    TransferRoute dormant = TransferRouteTestFixtures.inactiveExternalRoute(ROUTE_ID, partner, NOW);
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(dormant));
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(partner));

    assertThatThrownBy(
            () ->
                service.update(
                    ROUTE_ID,
                    updateCommand(PROVIDER_ID, DestinationType.EXTERNAL_ACCOUNT, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("TRANSFER_RAIL_UNAVAILABLE"));
  }

  @Test
  void updateActiveRouteRejectsUninstalledProviderRail() {
    TransferProvider partner =
        TransferProvider.create(
            PROVIDER_ID, "PARTNER", "Partner", RailType.PARTNER_NETWORK, true, false, NOW);
    TransferRoute active = TransferRouteTestFixtures.externalRoute(ROUTE_ID, partner, NOW);
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(active));
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(partner));

    assertThatThrownBy(
            () ->
                service.update(
                    ROUTE_ID,
                    updateCommand(PROVIDER_ID, DestinationType.EXTERNAL_ACCOUNT, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("TRANSFER_RAIL_UNAVAILABLE"));
  }

  @Test
  void updateInactiveRouteAllowsUninstalledProviderRail() {
    TransferProvider partner =
        TransferProvider.create(
            PROVIDER_ID, "PARTNER", "Partner", RailType.PARTNER_NETWORK, true, false, NOW);
    TransferRoute dormant = TransferRouteTestFixtures.inactiveExternalRoute(ROUTE_ID, partner, NOW);
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(dormant));
    when(providers.findByIdForUpdate(PROVIDER_ID)).thenReturn(Optional.of(partner));

    TransferRoute updated =
        service.update(
            ROUTE_ID, updateCommand(PROVIDER_ID, DestinationType.EXTERNAL_ACCOUNT, false, 0L));

    assertThat(updated.active()).isFalse();
  }

  @Test
  void updateRejectsStaleVersion() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));

    assertThatThrownBy(
            () ->
                service.update(
                    ROUTE_ID,
                    updateCommand(PROVIDER_ID, DestinationType.EXTERNAL_ACCOUNT, true, 99L)))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.code()).isEqualTo("STALE_ROUTE"));
  }

  @Test
  void deleteUnusedRouteHardDeletes() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(usage.routeUsed(ROUTE_ID)).thenReturn(false);

    DeletionResult result = service.delete(ROUTE_ID, 0L);

    assertThat(result.disposition()).isEqualTo(DeletionResult.Disposition.DELETED);
    assertThat(result.id()).isEqualTo(ROUTE_ID);
    verify(routes).delete(route);
  }

  @Test
  void deleteUsedRouteArchives() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(usage.routeUsed(ROUTE_ID)).thenReturn(true);

    DeletionResult result = service.delete(ROUTE_ID, 0L);

    assertThat(result.disposition()).isEqualTo(DeletionResult.Disposition.ARCHIVED);
    assertThat(route.active()).isFalse();
    assertThat(route.archivedAt()).isEqualTo(NOW);
    verify(routes, never()).delete(any());
  }

  @Test
  void deleteSystemProtectedRouteArchives() {
    TransferRoute system =
        TransferRoute.create(
            ROUTE_ID,
            provider,
            "FLUXPAY_INR_INTERNAL",
            "FluxPay INR Internal",
            DestinationType.INTERNAL_WALLET,
            null,
            "INR",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            5,
            new BigDecimal("99.00"),
            null,
            null,
            true,
            true,
            NOW);
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(system));
    when(usage.routeUsed(ROUTE_ID)).thenReturn(false);

    DeletionResult result = service.delete(ROUTE_ID, 0L);

    assertThat(result.disposition()).isEqualTo(DeletionResult.Disposition.ARCHIVED);
    verify(routes, never()).delete(any());
  }

  @Test
  void deleteRejectsStaleVersionAndMissingRoute() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));

    assertThatThrownBy(() -> service.delete(ROUTE_ID, 99L))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.code()).isEqualTo("STALE_ROUTE"));

    when(routes.findById(ROUTE_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.delete(ROUTE_ID, 0L))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.status().value()).isEqualTo(404));
  }

  private CreateRoute createCommand(String code, boolean active) {
    return new CreateRoute(
        PROVIDER_ID,
        code,
        "HDFC INR Standard",
        DestinationType.EXTERNAL_ACCOUNT,
        "IN",
        "INR",
        new BigDecimal("5.0000"),
        new BigDecimal("0.500000"),
        60,
        new BigDecimal("99.00"),
        new BigDecimal("1.0000"),
        new BigDecimal("500000.0000"),
        active);
  }

  private UpdateRoute updateCommand(
      UUID providerId, DestinationType destinationType, boolean active, Long version) {
    return new UpdateRoute(
        providerId,
        "HDFC INR Standard",
        destinationType,
        destinationType == DestinationType.EXTERNAL_ACCOUNT ? "IN" : null,
        "INR",
        new BigDecimal("5.0000"),
        new BigDecimal("0.500000"),
        60,
        new BigDecimal("99.00"),
        new BigDecimal("1.0000"),
        new BigDecimal("500000.0000"),
        active,
        version);
  }

  private static TransferRail fake(RailType type, DestinationType destination) {
    return new TransferRail() {
      @Override
      public RailType type() {
        return type;
      }

      @Override
      public Set<DestinationType> supportedDestinations() {
        return Set.of(destination);
      }

      @Override
      public TransferRailResult execute(TransferRailCommand command) {
        throw new UnsupportedOperationException();
      }
    };
  }
}
