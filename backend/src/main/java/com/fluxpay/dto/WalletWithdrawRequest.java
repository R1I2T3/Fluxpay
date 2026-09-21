package com.fluxpay.dto;

import java.util.UUID;

public record WalletWithdrawRequest(
    UUID bankAccountId, String currency, String amount, String note) {}
