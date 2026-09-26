package com.fluxpay.dto;

import java.time.Instant;
import java.time.LocalDate;

public record AdminStatisticsQuery(
    LocalDate from,
    LocalDate to,
    String currency,
    int currencyScale,
    Instant fromInclusive,
    Instant toExclusive,
    Instant generatedAt) {}
