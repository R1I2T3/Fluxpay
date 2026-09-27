package com.fluxpay.dto;

import com.fluxpay.domain.RailType;
import java.math.BigDecimal;

public record WalletTransferResponse(
    String sourceWalletId,
    String targetWalletId,
    String fromCurrency,
    String toCurrency,
    String sourceAmount,
    String fee,
    String netAmount,
    String creditedAmount,
    String rate,
    String quoteId,
    String journalReference,
    String providerCode,
    String routeCode,
    RailType railType,
    BigDecimal effectiveReliability) {}
