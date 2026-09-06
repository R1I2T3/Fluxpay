package com.fluxpay.common.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {
  private final SecretKey key;

  public JwtUtil(@Value("${fluxpay.jwt-secret}") String secret) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  public String generate(UUID userId, String email, String role) {
    return Jwts.builder()
        .subject(userId.toString())
        .claim("email", email)
        .claim("role", role)
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + 3600_000))
        .signWith(key)
        .compact();
  }

  public CurrentUser parse(String token) {
    Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    return new CurrentUser(
        UUID.fromString(c.getSubject()), c.get("email", String.class), c.get("role", String.class));
  }
}
