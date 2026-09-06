package com.fluxpay.common.contracts;
import java.math.BigDecimal;
import java.util.UUID;
public interface PayoutProvider { String submit(UUID paymentId, String routeCode, BigDecimal amount); }
