package com.fluxpay.service;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Shared catalogue fixtures for routing lifecycle service tests. */
final class TransferRouteTestFixtures {

  private TransferRouteTestFixtures() {}

  static TransferProvider provider(UUID id, Instant now) {
    return TransferProvider.create(
        id, "HDFC_BANK", "HDFC Bank", RailType.BANK_NETWORK, true, false, now);
  }

  static TransferRoute externalRoute(UUID id, TransferProvider provider, Instant now) {
    return TransferRoute.create(
        id,
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
        new BigDecimal("1.0000"),
        new BigDecimal("500000.0000"),
        true,
        false,
        now);
  }

  static TransferRoute inactiveExternalRoute(UUID id, TransferProvider provider, Instant now) {
    return TransferRoute.create(
        id,
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
        new BigDecimal("1.0000"),
        new BigDecimal("500000.0000"),
        false,
        false,
        now);
  }
}
