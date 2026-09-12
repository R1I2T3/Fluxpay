package com.fluxpay.dto;

public record WalletResponse(
    String walletId,
    String currency,
    String balance,
    String heldBalance,
    String availableBalance,
    String journalReference) {}
