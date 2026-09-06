package com.fluxpay.common.contracts;
import java.math.BigDecimal;
public interface FxRateProvider { BigDecimal rate(String from, String to); }
