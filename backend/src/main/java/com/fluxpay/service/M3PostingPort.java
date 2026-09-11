package com.fluxpay.service; import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
public interface M3PostingPort { void postApprovedPayment(UUID paymentId,UUID userId,UUID walletId,String currency,BigDecimal gross,BigDecimal fee,Instant quoteExpiry); }
