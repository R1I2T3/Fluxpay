package com.fluxpay.dto;

import com.fluxpay.beans.RecipientStatus;
import java.util.UUID;

public record RecipientResponse(
    UUID id,
    String name,
    String account,
    String bankName,
    String country,
    String currency,
    RecipientStatus status,
    long version) {}
