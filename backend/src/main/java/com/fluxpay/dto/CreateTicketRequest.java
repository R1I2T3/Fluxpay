package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateTicketRequest(
    UUID paymentId,
    @NotBlank @Size(max = 120) String subject,
    @NotBlank @Size(max = 4000) String body) {}
