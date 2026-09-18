package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoutingUsageServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");
  private static final UUID PROVIDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ROUTE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Mock private TransferRouteRepository routes;
  @Mock private PaymentQuoteRepository quotes;
  @Mock private PayoutAttemptRepository attempts;
  @Mock private TransferRouteOutcomeRepository outcomes;

  private RoutingUsageService usage;
  private TransferRoute route;

  @BeforeEach
  void setUp() {
    usage = new RoutingUsageService(routes, quotes, attempts, outcomes);
    TransferProvider provider = TransferRouteTestFixtures.provider(PROVIDER_ID, NOW);
    route = TransferRouteTestFixtures.externalRoute(ROUTE_ID, provider, NOW);
  }

  @Test
  void unusedRouteHasNoReferences() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(quotes.existsByRoute("HDFC_INR_STANDARD")).thenReturn(false);
    when(attempts.existsByRouteId(ROUTE_ID)).thenReturn(false);
    when(outcomes.existsByRouteId(ROUTE_ID)).thenReturn(false);

    assertThat(usage.routeUsed(ROUTE_ID)).isFalse();
  }

  @Test
  void quoteReferenceMarksRouteUsed() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(quotes.existsByRoute("HDFC_INR_STANDARD")).thenReturn(true);

    assertThat(usage.routeUsed(ROUTE_ID)).isTrue();
  }

  @Test
  void attemptReferenceMarksRouteUsed() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(quotes.existsByRoute("HDFC_INR_STANDARD")).thenReturn(false);
    when(attempts.existsByRouteId(ROUTE_ID)).thenReturn(true);

    assertThat(usage.routeUsed(ROUTE_ID)).isTrue();
  }

  @Test
  void outcomeReferenceMarksRouteUsed() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.of(route));
    when(quotes.existsByRoute("HDFC_INR_STANDARD")).thenReturn(false);
    when(attempts.existsByRouteId(ROUTE_ID)).thenReturn(false);
    when(outcomes.existsByRouteId(ROUTE_ID)).thenReturn(true);

    assertThat(usage.routeUsed(ROUTE_ID)).isTrue();
  }

  @Test
  void providerUsedWhenAnyChildRouteUsed() {
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID)).thenReturn(List.of(route));
    when(quotes.existsByRoute("HDFC_INR_STANDARD")).thenReturn(false);
    when(attempts.existsByRouteId(ROUTE_ID)).thenReturn(true);

    assertThat(usage.providerUsed(PROVIDER_ID)).isTrue();
  }

  @Test
  void providerUnusedWithoutChildren() {
    when(routes.findByProviderIdOrderByRouteCodeAsc(PROVIDER_ID)).thenReturn(List.of());

    assertThat(usage.providerUsed(PROVIDER_ID)).isFalse();
  }

  @Test
  void missingRouteCountsAsUnused() {
    when(routes.findById(ROUTE_ID)).thenReturn(Optional.empty());

    assertThat(usage.routeUsed(ROUTE_ID)).isFalse();
  }
}
