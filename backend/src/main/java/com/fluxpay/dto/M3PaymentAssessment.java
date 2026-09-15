package com.fluxpay.dto;
import com.fluxpay.common.enums.ScreeningVerdict;
import java.util.UUID;
public record M3PaymentAssessment(UUID assessmentId, UUID caseId, long sequence,
    String paymentFingerprint, ScreeningVerdict verdict, M3PaymentFacts facts) {}
