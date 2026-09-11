package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

public record CopilotRequest(@NotBlank String question, UUID paymentId) {}
