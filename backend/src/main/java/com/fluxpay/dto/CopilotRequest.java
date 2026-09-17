package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** A compliance-policy question, optionally associated with a payment under review. */
public record CopilotRequest(@NotBlank String question, UUID paymentId) {}
