package com.fluxpay.dto;

public record WalletConvertResponse(
    String sourceWalletId,
    String targetWalletId,
    String from,
    String to,
    String sourceAmount,
    String fee,
    String netAmount,
    String creditedAmount,
    String rate,
    String rateFetchedAt,
    boolean stale,
    boolean mock,
    String journalReference) {}
