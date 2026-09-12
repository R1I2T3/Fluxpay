package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Self-service profile update input; only the display name is mutable in this scope. */
public record UpdateProfileRequest(@NotBlank @Size(max = 255) String fullName) {}
