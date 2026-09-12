package com.fluxpay.dto;

import com.fluxpay.common.enums.KycStatus;
import java.util.UUID;

/** Safe user data returned by authentication and profile endpoints. */
public record UserResponse(
    UUID id, String email, String fullName, String role, KycStatus kycStatus) {}
