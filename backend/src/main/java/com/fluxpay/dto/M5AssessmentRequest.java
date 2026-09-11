package com.fluxpay.dto;

import java.util.UUID;

public record M5AssessmentRequest(UUID assessmentId, long assessmentSequence, UUID paymentId,
    String expectedPaymentFingerprint) {}
