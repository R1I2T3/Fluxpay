package com.fluxpay.dto;

import java.time.Instant;
import java.util.UUID;

public record TicketResponse(
    UUID id,
    UUID userId,
    UUID paymentId,
    String subject,
    String body,
    String status,
    UUID assigneeAdminId,
    Instant createdAt,
    Instant updatedAt) {}
