package com.fluxpay.domain;

import java.math.BigDecimal;
import java.util.UUID;

/** Immutable economics read from the selected persisted quote; never from current route pricing. */
public record AcceptedQuote(
    UUID quoteId,
    String routeCode,
    BigDecimal feeAmount,
    BigDecimal netSourceAmount,
    BigDecimal offeredRate,
    BigDecimal recipientAmount) {}
