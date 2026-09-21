package com.fluxpay.domain;

import java.math.BigDecimal;

/** One accepted conversion calculation, including the canonical persisted FX rate. */
public record ConversionCalculation(
    BigDecimal gross, BigDecimal fee, BigDecimal net, BigDecimal credit, BigDecimal rate) {}
