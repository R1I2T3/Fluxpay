package com.fluxpay.dto;
import java.math.BigDecimal; import java.time.Instant; import java.util.*;
public record QuoteResponse(UUID paymentId, UUID recommendedQuoteId, String recommendationReason, Instant expiresAt, Instant serverTime, List<Quote> quotes) { public record Quote(UUID id,String route,BigDecimal marketRate,BigDecimal offeredRate,BigDecimal feeAmount,BigDecimal recipientAmount,int estimatedMinutes,boolean recommended) {} }
