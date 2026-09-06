package com.fluxpay.common.security;
import java.util.UUID;
public record CurrentUser(UUID userId, String email, String role) {}
