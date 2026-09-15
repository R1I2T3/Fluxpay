package com.fluxpay.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Credentials supplied to the public login endpoint. */
public record LoginRequest(
    @NotBlank @Size(max = 255) String email, @NotBlank @Size(max = 72) String password) {}
