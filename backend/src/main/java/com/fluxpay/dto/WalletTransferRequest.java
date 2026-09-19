package com.fluxpay.dto;

import java.util.UUID;

public record WalletTransferRequest(
    UUID toUserId,
    String toEmail,
    String fromCurrency,
    String toCurrency,
    String amount,
    String amountMode,
    String note) {}
