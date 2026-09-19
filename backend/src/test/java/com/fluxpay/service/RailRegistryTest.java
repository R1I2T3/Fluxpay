package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.exception.BusinessException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RailRegistryTest {

  @Test
  void duplicateTypesFailAndCapabilitiesAreEnforced() {
    TransferRail bank = fake(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT);
    assertThat(new RailRegistry(List.of(bank)).require(RailType.BANK_NETWORK)).isSameAs(bank);
    assertThatThrownBy(() -> new RailRegistry(List.of(bank, bank)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BANK_NETWORK");
    assertThatThrownBy(
            () ->
                new RailRegistry(List.of(bank))
                    .requireCompatible(RailType.BANK_NETWORK, DestinationType.INTERNAL_WALLET))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
  }

  @Test
  void incompatibleRailFailsBeforeDelivery() {
    TransferRail bank = fake(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT);
    assertThatThrownBy(
            () ->
                new RailRegistry(List.of(bank))
                    .requireCompatible(RailType.BANK_NETWORK, DestinationType.INTERNAL_WALLET))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE");
              assertThat(error.status().value()).isEqualTo(400);
            });
  }

  @Test
  void compatibleRailResolves() {
    TransferRail bank = fake(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT);
    assertThat(
            new RailRegistry(List.of(bank))
                .requireCompatible(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT))
        .isSameAs(bank);
  }

  @Test
  void missingRailFailsWithUnavailableBeforeDelivery() {
    RailRegistry registry = new RailRegistry(List.of());
    assertThatThrownBy(() -> registry.require(RailType.BANK_NETWORK))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.code()).isEqualTo("TRANSFER_RAIL_UNAVAILABLE");
              assertThat(error.status().value()).isEqualTo(503);
            });
    assertThatThrownBy(
            () ->
                registry.requireCompatible(RailType.BANK_NETWORK, DestinationType.EXTERNAL_ACCOUNT))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("TRANSFER_RAIL_UNAVAILABLE"));
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
