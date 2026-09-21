package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.dto.TransferRoutingContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RouteEligibilityServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

  private RouteEligibilityService service;

  @BeforeEach
  void setUp() {
    service =
        new RouteEligibilityService(
            new RailRegistry(
                List.of(
                    fake(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT),
                    fake(RailType.INTERNAL_LEDGER, DestinationType.INTERNAL_WALLET))));
  }

  @Test
  void keepsCompatibleActiveExternalRoute() {
    TransferProvider provider = provider(RailType.BANK_NETWORK, true);
    TransferRoute route =
        external(provider, "HDFC_INR_STANDARD", "IN", "INR", new BigDecimal("99.00"));

    assertThat(service.filter(List.of(route), externalContext("IN", "INR"))).containsExactly(route);
  }

  @Test
  void excludesDestinationTypeMismatch() {
    TransferProvider bank = provider(RailType.BANK_NETWORK, true);
    TransferRoute external = external(bank, "HDFC_INR_STANDARD", "IN", "INR", rate("99.00"));
    TransferProvider ledger = provider(RailType.INTERNAL_LEDGER, true);
    TransferRoute internal = internal(ledger, "FLUXPAY_WALLET", null, "INR");

    assertThat(service.filter(List.of(external, internal), externalContext("IN", "INR")))
        .containsExactly(external);
    assertThat(service.filter(List.of(external, internal), internalContext("IN", "INR")))
        .containsExactly(internal);
  }

  @Test
  void excludesCountryAndCurrencyMismatch() {
    TransferProvider provider = provider(RailType.BANK_NETWORK, true);
    TransferRoute wrongCountry =
        external(provider, "HDFC_INR_STANDARD", "KE", "INR", rate("99.00"));
    TransferRoute wrongCurrency =
        external(provider, "HDFC_USD_STANDARD", "IN", "USD", rate("99.00"));

    assertThat(service.filter(List.of(wrongCountry, wrongCurrency), externalContext("IN", "INR")))
        .isEmpty();
  }

  @Test
  void excludesInactiveOrArchivedRouteAndInactiveOrArchivedProvider() {
    TransferProvider activeProvider = provider(RailType.BANK_NETWORK, true);
    TransferProvider idleProvider = provider(RailType.BANK_NETWORK, false);
    TransferProvider archivedProvider = provider(RailType.BANK_NETWORK, true);
    archivedProvider.archive(NOW);
    TransferRoute active = external(activeProvider, "HDFC_INR_ACTIVE", "IN", "INR", rate("99.00"));
    TransferRoute idle = external(activeProvider, "HDFC_INR_IDLE", "IN", "INR", rate("99.00"));
    idle.update(
        idle.provider(),
        idle.name(),
        idle.destinationType(),
        idle.destinationCountry(),
        idle.payoutCurrency(),
        idle.baseFee(),
        idle.fxSpreadPercentage(),
        idle.estimatedMinutes(),
        idle.configuredSuccessRate(),
        idle.minimumRecipientAmount(),
        idle.maximumRecipientAmount(),
        false,
        NOW);
    TransferRoute archived =
        external(activeProvider, "HDFC_INR_ARCHIVED", "IN", "INR", rate("99.00"));
    archived.archive(NOW);
    TransferRoute idleProviderRoute =
        external(idleProvider, "HDFC_INR_IDLE_PROVIDER", "IN", "INR", rate("99.00"));
    TransferRoute archivedProviderRoute =
        external(archivedProvider, "HDFC_INR_ARCHIVED_PROVIDER", "IN", "INR", rate("99.00"));

    assertThat(
            service.filter(
                List.of(active, idle, archived, idleProviderRoute, archivedProviderRoute),
                externalContext("IN", "INR")))
        .containsExactly(active);
  }

  @Test
  void excludesIncompatibleOrMissingRail() {
    TransferProvider bank = provider(RailType.BANK_NETWORK, true);
    TransferProvider ledger = provider(RailType.INTERNAL_LEDGER, true);
    TransferProvider partner =
        TransferProvider.create(
            UUID.randomUUID(),
            "PARTNER_PROVIDER",
            "Partner",
            RailType.PARTNER_NETWORK,
            true,
            false,
            NOW);
    // BANK_NETWORK never supports internal destinations; PARTNER_NETWORK is not installed.
    TransferRoute bankForInternal = internal(bank, "BANK_INTERNAL", null, "INR");
    TransferRoute partnerForExternal =
        external(partner, "PARTNER_INR_STANDARD", "IN", "INR", rate("99.00"));
    TransferRoute ledgerForInternal = internal(ledger, "FLUXPAY_WALLET", null, "INR");

    assertThat(
            service.filter(
                List.of(bankForInternal, partnerForExternal, ledgerForInternal),
                internalContext("IN", "INR")))
        .containsExactly(ledgerForInternal);
    assertThat(service.filter(List.of(partnerForExternal), externalContext("IN", "INR"))).isEmpty();
  }

  @Test
  void internalNullCountryIsAnAllCountryWildcard() {
    TransferProvider ledger = provider(RailType.INTERNAL_LEDGER, true);
    TransferRoute wildcard = internal(ledger, "FLUXPAY_WALLET", null, "INR");
    TransferRoute pinned = internal(ledger, "FLUXPAY_IN_WALLET", "IN", "INR");

    assertThat(service.filter(List.of(wildcard, pinned), internalContext("KE", "INR")))
        .containsExactly(wildcard);
    assertThat(service.filter(List.of(wildcard, pinned), internalContext("IN", "INR")))
        .containsExactly(wildcard, pinned);
  }

  @Test
  void emptyCatalogueStaysEmpty() {
    assertThat(service.filter(List.of(), externalContext("IN", "INR"))).isEmpty();
  }

  private static TransferRoutingContext externalContext(String country, String currency) {
    return new TransferRoutingContext(
        DestinationType.EXTERNAL_ACCOUNT,
        country,
        currency,
        new BigDecimal("100"),
        new BigDecimal("80"));
  }

  private static TransferRoutingContext internalContext(String country, String currency) {
    return new TransferRoutingContext(
        DestinationType.INTERNAL_WALLET,
        country,
        currency,
        new BigDecimal("100"),
        new BigDecimal("80"));
  }

  private static TransferProvider provider(RailType railType, boolean active) {
    return TransferProvider.create(
        UUID.randomUUID(), "TEST_PROVIDER", "Test Provider", railType, active, false, NOW);
  }

  private static TransferRoute external(
      TransferProvider provider, String code, String country, String currency, BigDecimal rate) {
    return TransferRoute.create(
        UUID.randomUUID(),
        provider,
        code,
        code + " name",
        DestinationType.EXTERNAL_ACCOUNT,
        country,
        currency,
        new BigDecimal("5.0000"),
        new BigDecimal("0.500000"),
        60,
        rate,
        null,
        null,
        true,
        false,
        NOW);
  }

  private static TransferRoute internal(
      TransferProvider provider, String code, String country, String currency) {
    return TransferRoute.create(
        UUID.randomUUID(),
        provider,
        code,
        code + " name",
        DestinationType.INTERNAL_WALLET,
        country,
        currency,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        5,
        rate("100.00"),
        null,
        null,
        true,
        false,
        NOW);
  }

  private static BigDecimal rate(String value) {
    return new BigDecimal(value);
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
