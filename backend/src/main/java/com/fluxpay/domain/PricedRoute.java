package com.fluxpay.domain;

import java.math.BigDecimal;

public record PricedRoute(
    BigDecimal netSourceAmount, BigDecimal offeredRate, BigDecimal recipientAmount) {}
