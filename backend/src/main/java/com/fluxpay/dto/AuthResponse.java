package com.fluxpay.dto;

/** Authentication result returned after successful registration or login. */
public record AuthResponse(String token, String tokenType, long expiresIn, UserResponse user) {
  public static final String TOKEN_TYPE = "Bearer";
  public static final long EXPIRES_IN_SECONDS = 3600;
}
