package com.fluxpay.dto;

import java.util.UUID;

public record M3PostingAccounts(UUID customerWalletId, UUID clearingWalletId, UUID feeWalletId) {}
