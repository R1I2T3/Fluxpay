package com.fluxpay.beans;

import com.fluxpay.dto.M5AssessmentResponse;
import com.fluxpay.dto.M5PaymentSnapshot;
import java.time.Instant;
import java.util.UUID;

public record M5ScreeningCase(M5AssessmentResponse assessment, M5PaymentSnapshot snapshot,
    String requestFingerprint, String status, String verdict, String suggestedAction,
    String paymentDisposition, UUID reviewReference, UUID decidedBy, Instant decidedAt,
    String decisionReason, long version) {}
