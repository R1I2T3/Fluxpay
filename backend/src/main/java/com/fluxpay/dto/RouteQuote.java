package com.fluxpay.dto;

import com.fluxpay.beans.TransferRoute;
import java.math.BigDecimal;

/**
 * PRD quote shape: the evaluated route plus the market rate, offered rate, net amount, and the
 * learned effective reliability attached at pricing time.
 */
public record RouteQuote(
    TransferRoute route,
    BigDecimal marketRate,
    BigDecimal offeredRate,
    BigDecimal recipientAmount,
    BigDecimal feeAmount,
    BigDecimal netSourceAmount,
    BigDecimal effectiveReliability) {}
