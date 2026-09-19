package com.fluxpay.dto;

import com.fluxpay.domain.TransferDestination;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable rail execution command. The idempotency key identifies the persisted attempt; repeating
 * an identical command must replay the same result.
 */
public record TransferRailCommand(
    UUID transferId,
    UUID attemptId,
    UUID senderUserId,
    BigDecimal sourceAmount,
    String sourceCurrency,
    BigDecimal recipientAmount,
    String targetCurrency,
    TransferProviderSnapshot provider,
    TransferRouteSnapshot route,
    TransferDestination destination,
    int attemptNumber,
    BigDecimal customerFee,
    BigDecimal offeredRate,
    String idempotencyKey) {
  public TransferRailCommand {
    Objects.requireNonNull(transferId, "transferId must not be null");
    if (attemptId == null || !("payout:" + attemptId).equals(idempotencyKey)) {
      throw new IllegalArgumentException("Provider key must identify the persisted payout attempt");
    }
    Objects.requireNonNull(senderUserId, "senderUserId must not be null");
    if (sourceAmount == null || sourceAmount.signum() <= 0) {
      throw new IllegalArgumentException("sourceAmount must be positive");
    }
    if (sourceCurrency == null || sourceCurrency.isBlank()) {
      throw new IllegalArgumentException("sourceCurrency must not be blank");
    }
    if (targetCurrency == null || targetCurrency.isBlank()) {
      throw new IllegalArgumentException("targetCurrency must not be blank");
    }
    Objects.requireNonNull(provider, "provider must not be null");
    Objects.requireNonNull(route, "route must not be null");
    Objects.requireNonNull(destination, "destination must not be null");
    if (customerFee == null || customerFee.signum() < 0) {
      throw new IllegalArgumentException("customerFee must not be negative");
    }
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("attemptNumber must be at least 1");
    }
  }
}
