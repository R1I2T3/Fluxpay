package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** Admin-controlled ticket workflow update. */
public record UpdateTicketRequest(@NotBlank String status, UUID assigneeAdminId) {}
