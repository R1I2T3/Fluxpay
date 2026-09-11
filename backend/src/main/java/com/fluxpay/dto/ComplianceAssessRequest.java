package com.fluxpay.dto;

/**
 * Optional hints for {@code POST /api/compliance/assess/{paymentId}}. The current {@code
 * payments}/{@code recipients} schema (V301) has no payment-purpose or destination-country
 * column, so those two Payment Passport rules only run when the caller supplies them here; when
 * omitted, that rule is skipped rather than penalizing data we don't have.
 */
public record ComplianceAssessRequest(String paymentPurpose, Boolean highRiskDestination) {}
