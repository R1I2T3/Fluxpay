package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
  void rejectsBlankSourceCurrency() {
    TransferProvider p = provider(RailType.BANK_NETWORK, true);
    assertThatThrownBy(
            () ->
                TransferRoute.create(
                    UUID.randomUUID(),
                    p,
                    "SRC_BAD",
                    "bad",
                    DestinationType.EXTERNAL_ACCOUNT,
                    "IN",
                    null,
                    "US",
                    "INR",
                    new BigDecimal("5.0000"),
                    new BigDecimal("0.5"),
                    60,
                    new BigDecimal("99.00"),
                    null,
                    null,
                    true,
                    false,
                    NOW))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void keepsCompatibleActiveExternalRoute() {
    TransferProvider provider = provider(RailType.BANK_NETWORK, true);
    TransferRoute route =
        external(provider, "HDFC_INR_STANDARD", null, "USD", "IN", "INR", new BigDecimal("99.00"));

    assertThat(service.filter(List.of(route), externalContext("USD", null, "IN", "INR")))
        .containsExactly(route);
  }

  @Test
  void excludesDestinationTypeMismatch() {
    TransferProvider bank = provider(RailType.BANK_NETWORK, true);
    TransferRoute external =
        external(bank, "HDFC_INR_STANDARD", null, "USD", "IN", "INR", rate("99.00"));
    TransferProvider ledger = provider(RailType.INTERNAL_LEDGER, true);
    TransferRoute internal = internal(ledger, "FLUXPAY_WALLET", null, "INR");

    assertThat(
            service.filter(List.of(external, internal), externalContext("USD", null, "IN", "INR")))
        .containsExactly(external);
    assertThat(service.filter(List.of(external, internal), internalContext("IN", "INR")))
        .containsExactly(internal);
  }

  @Test
  void excludesCountryAndCurrencyMismatch() {
    TransferProvider provider = provider(RailType.BANK_NETWORK, true);
    TransferRoute wrongCountry =
        external(provider, "HDFC_INR_STANDARD", null, "USD", "KE", "INR", rate("99.00"));
    TransferRoute wrongCurrency =
        external(provider, "HDFC_USD_STANDARD", null, "USD", "IN", "USD", rate("99.00"));

    assertThat(
            service.filter(
                List.of(wrongCountry, wrongCurrency), externalContext("USD", null, "IN", "INR")))
        .isEmpty();
  }

  @Test
  void excludesInactiveOrArchivedRouteAndInactiveOrArchivedProvider() {
    TransferProvider activeProvider = provider(RailType.BANK_NETWORK, true);
    TransferProvider idleProvider = provider(RailType.BANK_NETWORK, false);
    TransferProvider archivedProvider = provider(RailType.BANK_NETWORK, true);
    archivedProvider.archive(NOW);
    TransferRoute active =
        external(activeProvider, "HDFC_INR_ACTIVE", null, "USD", "IN", "INR", rate("99.00"));
    TransferRoute idle =
        external(activeProvider, "HDFC_INR_IDLE", null, "USD", "IN", "INR", rate("99.00"));
    idle.update(
        idle.provider(),
        idle.name(),
        idle.destinationType(),
        idle.destinationCountry(),
        idle.sourceCountry(),
        idle.sourceCurrency(),
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
        external(activeProvider, "HDFC_INR_ARCHIVED", null, "USD", "IN", "INR", rate("99.00"));
    archived.archive(NOW);
    TransferRoute idleProviderRoute =
        external(idleProvider, "HDFC_INR_IDLE_PROVIDER", null, "USD", "IN", "INR", rate("99.00"));
    TransferRoute archivedProviderRoute =
        external(
            archivedProvider,
            "HDFC_INR_ARCHIVED_PROVIDER",
            null,
            "USD",
            "IN",
            "INR",
            rate("99.00"));

    assertThat(
            service.filter(
                List.of(active, idle, archived, idleProviderRoute, archivedProviderRoute),
                externalContext("USD", null, "IN", "INR")))
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
        external(partner, "PARTNER_INR_STANDARD", null, "USD", "IN", "INR", rate("99.00"));
    TransferRoute ledgerForInternal = internal(ledger, "FLUXPAY_WALLET", null, "INR");

    assertThat(
            service.filter(
                List.of(bankForInternal, partnerForExternal, ledgerForInternal),
                internalContext("IN", "INR")))
        .containsExactly(ledgerForInternal);
    assertThat(
            service.filter(List.of(partnerForExternal), externalContext("USD", null, "IN", "INR")))
        .isEmpty();
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
  void excludesSourceCurrencyMismatch() {
    TransferProvider p = provider(RailType.BANK_NETWORK, true);
    TransferRoute usd = external(p, "USD_INR", null, "USD", "IN", "INR", rate("99.00"));
    assertThat(service.filter(List.of(usd), externalContext("AED", null, "IN", "INR"))).isEmpty();
    assertThat(service.filter(List.of(usd), externalContext("USD", null, "IN", "INR")))
        .containsExactly(usd);
  }

  @Test
  void nullSourceCountryIsWildcardButPinnedCountryMustMatch() {
    TransferProvider p = provider(RailType.BANK_NETWORK, true);
    TransferRoute wildcard = external(p, "WILD", null, "USD", "IN", "INR", rate("99.00"));
    TransferRoute pinned = external(p, "PINNED", "US", "USD", "IN", "INR", rate("99.00"));
    assertThat(service.filter(List.of(wildcard, pinned), externalContext("USD", "US", "IN", "INR")))
        .containsExactly(wildcard, pinned);
    assertThat(service.filter(List.of(wildcard, pinned), externalContext("USD", "AE", "IN", "INR")))
        .containsExactly(wildcard);
  }

  @Test
  void emptyCatalogueStaysEmpty() {
    assertThat(service.filter(List.of(), externalContext("USD", null, "IN", "INR"))).isEmpty();
  }

  private static TransferRoutingContext externalContext(
      String sourceCurrency, String sourceCountry, String country, String currency) {
    return new TransferRoutingContext(
        sourceCurrency,
        sourceCountry,
        DestinationType.EXTERNAL_ACCOUNT,
        country,
        currency,
        new BigDecimal("100"),
        new BigDecimal("80"));
  }

  private static TransferRoutingContext internalContext(String country, String currency) {
    return new TransferRoutingContext(
        currency,
        null,
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
      TransferProvider provider,
      String code,
      String sourceCountry,
      String sourceCurrency,
      String country,
      String currency,
      BigDecimal rate) {
    return TransferRoute.create(
        UUID.randomUUID(),
        provider,
        code,
        code + " name",
        DestinationType.EXTERNAL_ACCOUNT,
        country,
        sourceCountry,
        sourceCurrency,
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
        null,
        currency,
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
