package com.fluxpay.dto;

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
    String journalReference) {}
