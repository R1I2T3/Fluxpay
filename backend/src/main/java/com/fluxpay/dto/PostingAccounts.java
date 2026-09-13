package com.fluxpay.dto;

import java.util.UUID;

public record PostingAccounts(UUID customerWalletId, UUID clearingWalletId, UUID feeWalletId) {}
