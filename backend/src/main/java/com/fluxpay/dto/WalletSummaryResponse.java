package com.fluxpay.dto;

public record WalletSummaryResponse(
    String walletId, String currency, String heldBalance, String availableBalance) {}
