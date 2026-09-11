package com.fluxpay.beans;
import java.util.UUID;
public record M5ScreeningHead(UUID paymentId, UUID latestCaseId, long latestSequence, long version) {}
