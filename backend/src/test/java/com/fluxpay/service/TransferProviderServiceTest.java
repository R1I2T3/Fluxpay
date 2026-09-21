package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.fluxpay.service.TransferProviderService.CreateProvider;
import com.fluxpay.service.TransferProviderService.UpdateProvider;
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
class TransferProviderServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
  private static final UUID PROVIDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ROUTE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock private TransferProviderRepository providers;
  @Mock private TransferRouteRepository routes;
  @Mock private RoutingUsageService usage;

  private TransferProviderService service;
  private TransferProvider provider;

  @BeforeEach
  void setUp() {
    RailRegistry rails =
        new RailRegistry(
            List.of(
                fake(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT),
                fake(RailType.REAL_TIME_NETWORK, DestinationType.EXTERNAL_ACCOUNT),
                fake(RailType.INTERNAL_LEDGER, DestinationType.INTERNAL_WALLET)));
    service =
        new TransferProviderService(
            providers, routes, usage, rails, Clock.fixed(NOW, ZoneOffset.UTC));
    provider =
        TransferProvider.create(
            PROVIDER_ID, "HDFC_BANK", "HDFC Bank", RailType.BANK_NETWORK, true, false, NOW);
  }

  @Test
  void usedProviderArchivesAndCannotChangeRail() {
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(usage.providerUsed(PROVIDER_ID)).thenReturn(true);

    assertThatThrownBy(
            () ->
                service.update(
                    PROVIDER_ID,
                    new UpdateProvider(
                        "HDFC Bank", RailType.REAL_TIME_NETWORK, true, provider.version())))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("ROUTING_BINDING_IMMUTABLE"));

    assertThat(service.delete(PROVIDER_ID, provider.version()).disposition())
        .isEqualTo(DeletionResult.Disposition.ARCHIVED);
  }

  @Test
  void createNormalizesCodeAndRejectsDuplicates() {
    when(providers.findByProviderCode("HDFC_BANK")).thenReturn(Optional.empty());
    when(providers.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    TransferProvider created =
        service.create(new CreateProvider("hdfc_bank", "HDFC Bank", RailType.BANK_NETWORK, true));

    assertThat(created.code()).isEqualTo("HDFC_BANK");
    assertThat(created.systemProtected()).isFalse();

    when(providers.findByProviderCode("HDFC_BANK")).thenReturn(Optional.of(provider));
    assertThatThrownBy(
            () ->
                service.create(
                    new CreateProvider("HDFC_BANK", "HDFC Bank", RailType.BANK_NETWORK, true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("PROVIDER_CODE_CONFLICT");
              assertThat(error.status().value()).isEqualTo(409);
            });
  }

  @Test
  void createRejectsMalformedCode() {
    assertThatThrownBy(
            () ->
                service.create(new CreateProvider("bad-code!", "Bad", RailType.BANK_NETWORK, true)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE");
              assertThat(error.status().value()).isEqualTo(400);
            });
  }

  @Test
  void updateNameAndRailBeforeUse() {
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(usage.providerUsed(PROVIDER_ID)).thenReturn(false);

    TransferProvider updated =
        service.update(
            PROVIDER_ID, new UpdateProvider("HDFC Bank Ltd", RailType.REAL_TIME_NETWORK, true, 0L));

    assertThat(updated.name()).isEqualTo("HDFC Bank Ltd");
    assertThat(updated.railType()).isEqualTo(RailType.REAL_TIME_NETWORK);
    verify(providers).flush();
  }

  @Test
  void updateRejectsStaleVersion() {
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));

    assertThatThrownBy(
            () ->
                service.update(
                    PROVIDER_ID, new UpdateProvider("HDFC Bank", RailType.BANK_NETWORK, true, 99L)))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.code()).isEqualTo("STALE_PROVIDER"));
  }

  @Test
  void updateRejectsMissingProvider() {
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.update(
                    PROVIDER_ID, new UpdateProvider("HDFC Bank", RailType.BANK_NETWORK, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.status().value()).isEqualTo(404));
  }

  @Test
  void updateRejectsRailIncompatibleWithChildRoutes() {
    TransferRoute child = route(provider);
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(usage.providerUsed(PROVIDER_ID)).thenReturn(false);
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID)).thenReturn(List.of(child));

    assertThatThrownBy(
            () ->
                service.update(
                    PROVIDER_ID,
                    new UpdateProvider("HDFC Bank", RailType.INTERNAL_LEDGER, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
  }

  @Test
  void updateRejectsDeactivationWhileActiveNonArchivedRoutesExist() {
    TransferRoute child = route(provider);
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID)).thenReturn(List.of(child));

    assertThatThrownBy(
            () ->
                service.update(
                    PROVIDER_ID, new UpdateProvider("HDFC Bank", RailType.BANK_NETWORK, false, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("PROVIDER_HAS_ROUTES");
              assertThat(error.status().value()).isEqualTo(409);
            });

    assertThat(provider.active()).isTrue();
  }

  @Test
  void updateAllowsDeactivationWhenChildrenAreInactiveOrArchived() {
    TransferRoute inactive =
        TransferRouteTestFixtures.inactiveExternalRoute(ROUTE_ID, provider, NOW);
    TransferRoute archived =
        TransferRouteTestFixtures.externalRoute(UUID.randomUUID(), provider, NOW);
    archived.archive(NOW);
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID))
        .thenReturn(List.of(inactive, archived));

    TransferProvider updated =
        service.update(
            PROVIDER_ID, new UpdateProvider("HDFC Bank", RailType.BANK_NETWORK, false, 0L));

    assertThat(updated.active()).isFalse();
  }

  @Test
  void updateActiveRailBindingRejectsUninstalledRailForActiveRoute() {
    TransferRoute child = route(provider);
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(usage.providerUsed(PROVIDER_ID)).thenReturn(false);
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID)).thenReturn(List.of(child));

    assertThatThrownBy(
            () ->
                service.update(
                    PROVIDER_ID,
                    new UpdateProvider("HDFC Bank", RailType.PARTNER_NETWORK, true, 0L)))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("TRANSFER_RAIL_UNAVAILABLE");
              assertThat(error.status().value()).isEqualTo(503);
            });

    assertThat(provider.railType()).isEqualTo(RailType.BANK_NETWORK);
  }

  @Test
  void updateRailBindingIgnoresInactiveAndArchivedRoutes() {
    TransferRoute inactive =
        TransferRouteTestFixtures.inactiveExternalRoute(ROUTE_ID, provider, NOW);
    TransferRoute archived =
        TransferRouteTestFixtures.externalRoute(UUID.randomUUID(), provider, NOW);
    archived.archive(NOW);
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(usage.providerUsed(PROVIDER_ID)).thenReturn(false);
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID))
        .thenReturn(List.of(inactive, archived));

    assertThatCode(
            () ->
                service.update(
                    PROVIDER_ID,
                    new UpdateProvider("HDFC Bank", RailType.INTERNAL_LEDGER, true, 0L)))
        .doesNotThrowAnyException();

    assertThat(provider.railType()).isEqualTo(RailType.INTERNAL_LEDGER);
  }

  @Test
  void deleteUnusedProviderHardDeletes() {
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID)).thenReturn(List.of());
    when(usage.providerUsed(PROVIDER_ID)).thenReturn(false);

    DeletionResult result = service.delete(PROVIDER_ID, 0L);

    assertThat(result.disposition()).isEqualTo(DeletionResult.Disposition.DELETED);
    assertThat(result.id()).isEqualTo(PROVIDER_ID);
    verify(providers).delete(provider);
  }

  @Test
  void deleteSystemProtectedProviderArchives() {
    TransferProvider system =
        TransferProvider.create(
            PROVIDER_ID, "FLUXPAY", "FluxPay", RailType.INTERNAL_LEDGER, true, true, NOW);
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(system));
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID)).thenReturn(List.of());
    when(usage.providerUsed(PROVIDER_ID)).thenReturn(false);

    DeletionResult result = service.delete(PROVIDER_ID, 0L);

    assertThat(result.disposition()).isEqualTo(DeletionResult.Disposition.ARCHIVED);
    assertThat(system.active()).isFalse();
    assertThat(system.archivedAt()).isEqualTo(NOW);
    verify(providers, never()).delete(any());
  }

  @Test
  void deleteConflictsWhileNonArchivedChildrenExist() {
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID))
        .thenReturn(List.of(route(provider)));

    assertThatThrownBy(() -> service.delete(PROVIDER_ID, 0L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("PROVIDER_HAS_ROUTES"));
    verify(providers, never()).delete(any());
  }

  @Test
  void deleteArchivesWhenOnlyArchivedChildrenRemain() {
    TransferRoute archived = route(provider);
    archived.archive(NOW);
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID)).thenReturn(List.of(archived));
    when(usage.providerUsed(PROVIDER_ID)).thenReturn(false);

    DeletionResult result = service.delete(PROVIDER_ID, 0L);

    // Physical deletion would violate the route provider_id foreign key, so the
    // provider archives to preserve catalogue history.
    assertThat(result.disposition()).isEqualTo(DeletionResult.Disposition.ARCHIVED);
    verify(providers, never()).delete(any());
  }

  @Test
  void deleteRejectsStaleVersion() {
    when(providers.findById(PROVIDER_ID)).thenReturn(Optional.of(provider));

    assertThatThrownBy(() -> service.delete(PROVIDER_ID, 99L))
        .isInstanceOfSatisfying(
            BusinessException.class, error -> assertThat(error.code()).isEqualTo("STALE_PROVIDER"));
  }

  private TransferRoute route(TransferProvider owner) {
    return TransferRouteTestFixtures.externalRoute(ROUTE_ID, owner, NOW);
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
