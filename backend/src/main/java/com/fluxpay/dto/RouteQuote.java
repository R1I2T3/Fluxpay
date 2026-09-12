package com.fluxpay.dto;

import com.fluxpay.beans.PayoutRoute;
import java.math.BigDecimal;

/** PRD quote shape: the evaluated route plus the market rate, offered rate and net amount. */
public record RouteQuote(
    PayoutRoute route, BigDecimal marketRate, BigDecimal offeredRate, BigDecimal recipientAmount) {}
