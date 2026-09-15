package com.fluxpay.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletSnapshot(
    UUID id, UUID ownerId, String currency, BigDecimal availableFunds, boolean customerEligible) {}
