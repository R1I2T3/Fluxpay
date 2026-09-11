package com.fluxpay.beans;
import com.fluxpay.dto.M5ReviewCommand;
import java.time.Instant;
public record M5ReviewDecision(M5ReviewCommand command, String deliveryState, int retryCount,
    Instant nextAttemptAt, String lastErrorCode) {}
