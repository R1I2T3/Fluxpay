package com.fluxpay.dto;
import com.fluxpay.beans.PaymentLifecycleStatus; import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
public record PaymentResponse(UUID id, UUID sourceWalletId, UUID recipientId, BigDecimal sourceAmount, String sourceCurrency, String payoutCurrency, PaymentLifecycleStatus status, UUID selectedQuoteId, Instant createdAt, boolean legacy) {}
