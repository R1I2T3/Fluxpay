package com.fluxpay.config;

import com.fluxpay.common.contracts.*;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.dto.M3PostingAccounts;
import com.fluxpay.dto.M3WalletSnapshot;
import com.fluxpay.service.M3WalletPort;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.*;

/** Local-only test adapters for APIs owned by other modules. */
@Configuration
@Profile("local")
public class M3PaymentTestConfig {
  @Bean Clock m3Clock() { return Clock.systemUTC(); }
  @Bean M3WalletPort m3WalletPort() {
    return new M3WalletPort() {
      @Override public Optional<M3WalletSnapshot> findOwned(UUID userId, UUID walletId) {
        return Optional.of(new M3WalletSnapshot(walletId, userId, "USD", new BigDecimal("1000000.0000"), true));
      }
      @Override public M3PostingAccounts lockPostingAccounts(UUID userId, UUID walletId, String currency, BigDecimal gross) {
        return new M3PostingAccounts(walletId, UUID.nameUUIDFromBytes("local-clearing".getBytes()), UUID.nameUUIDFromBytes("local-fee".getBytes()));
      }
    };
  }
  @Bean KycGate kycGate() { return userId -> true; }
  @Bean ComplianceAssessor complianceAssessor() { return (userId, amount, currency) -> ScreeningVerdict.APPROVE; }
  @Bean FxRateProvider fxRateProvider() { return (from, to) -> new BigDecimal("0.900000"); }
  @Bean LedgerWriter ledgerWriter() { return (walletId, entryType, amount, currency, idempotencyKey) -> {}; }
  @Bean PayoutProvider payoutProvider() { return (paymentId, routeCode, amount) -> "local-" + paymentId; }
  @Bean EmbeddingProvider embeddingProvider() { return text -> new float[] {0.0f}; }
}
