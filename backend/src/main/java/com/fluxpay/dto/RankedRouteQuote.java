package com.fluxpay.dto;

import java.math.BigDecimal;

public record RankedRouteQuote(RouteQuote quote, BigDecimal score, int position) {}
