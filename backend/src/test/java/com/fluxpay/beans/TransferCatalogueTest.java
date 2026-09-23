package com.fluxpay.beans;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransferCatalogueTest {

  private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

  @Test
  void codesNormalizeAndUsedIdentityCanBeArchived() {
    TransferProvider provider =
        TransferProvider.create(
            UUID.randomUUID(), "hdfc_bank", "HDFC Bank", RailType.BANK_NETWORK, true, false, NOW);
    TransferRoute route =
        TransferRoute.create(
            UUID.randomUUID(),
            provider,
            "hdfc_inr_standard",
            "HDFC INR Standard",
            DestinationType.EXTERNAL_ACCOUNT,
            "in",
            "inr",
            new BigDecimal("5.0000"),
            new BigDecimal("0.500000"),
            60,
            new BigDecimal("99.00"),
            new BigDecimal("1.0000"),
            new BigDecimal("500000.0000"),
            true,
            false,
            NOW);

    assertThat(provider.code()).isEqualTo("HDFC_BANK");
    assertThat(route.code()).isEqualTo("HDFC_INR_STANDARD");
    route.archive(NOW.plusSeconds(1));
    assertThat(route.active()).isFalse();
    assertThat(route.archivedAt()).isEqualTo(NOW.plusSeconds(1));
  }

  @Test
  void rejectsMalformedCodes() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                TransferProvider.create(
                    UUID.randomUUID(),
                    "bad-code",
                    "Provider",
                    RailType.BANK_NETWORK,
                    true,
                    false,
                    NOW));
  }

  @Test
  void rejectsExternalRouteWithoutCountry() {
    TransferProvider provider = provider();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                route(
                    provider,
                    DestinationType.EXTERNAL_ACCOUNT,
                    null,
                    new BigDecimal("5.0000"),
                    new BigDecimal("0.500000"),
                    60,
                    new BigDecimal("99.00"),
                    new BigDecimal("1.0000"),
                    new BigDecimal("500000.0000")));
  }

  @Test
  void rejectsInvalidCommercialMetrics() {
    TransferProvider provider = provider();

    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                route(
                    provider,
                    DestinationType.EXTERNAL_ACCOUNT,
                    "IN",
                    BigDecimal.ZERO,
                    new BigDecimal("-0.500000"),
                    0,
                    new BigDecimal("101.00"),
                    new BigDecimal("1.0000"),
                    new BigDecimal("500000.0000")));
  }

  @Test
  void rejectsReversedRecipientLimits() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                route(
                    provider(),
                    DestinationType.EXTERNAL_ACCOUNT,
                    "IN",
                    new BigDecimal("5.0000"),
                    new BigDecimal("0.500000"),
                    60,
                    new BigDecimal("99.00"),
                    new BigDecimal("500000.0000"),
                    new BigDecimal("1.0000")));
  }

  private static TransferProvider provider() {
    return TransferProvider.create(
        UUID.randomUUID(), "HDFC_BANK", "HDFC Bank", RailType.BANK_NETWORK, true, false, NOW);
  }

  private static TransferRoute route(
      TransferProvider provider,
      DestinationType destinationType,
      String country,
      BigDecimal fee,
      BigDecimal spread,
      int eta,
      BigDecimal successRate,
      BigDecimal minimum,
      BigDecimal maximum) {
    return TransferRoute.create(
        UUID.randomUUID(),
        provider,
        "HDFC_INR_STANDARD",
        "HDFC INR Standard",
        destinationType,
        country,
        "INR",
        fee,
        spread,
        eta,
        successRate,
        minimum,
        maximum,
        true,
        false,
        NOW);
  }
}
