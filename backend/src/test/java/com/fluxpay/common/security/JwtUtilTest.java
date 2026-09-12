package com.fluxpay.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtUtilTest {
  private static final String SECRET = "test-jwt-secret-must-be-at-least-32-bytes-long";

  private final JwtUtil jwt = new JwtUtil(SECRET);

  @Test
  void generatesAndParsesValidOneHourToken() {
    UUID userId = UUID.randomUUID();

    String token = jwt.generate(userId, "user@fluxpay.test", "USER");
    CurrentUser currentUser = jwt.parse(token);

    assertThat(currentUser.userId()).isEqualTo(userId);
    assertThat(currentUser.email()).isEqualTo("user@fluxpay.test");
    assertThat(currentUser.role()).isEqualTo("USER");
  }

  @Test
  void rejectsTamperedToken() {
    String token = jwt.generate(UUID.randomUUID(), "user@fluxpay.test", "USER");
    String tampered = token.substring(0, token.length() - 1) + "x";

    assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void rejectsSignedExpiredToken() {
    Instant now = Instant.now();
    String expired =
        Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim("email", "user@fluxpay.test")
            .claim("role", "USER")
            .issuedAt(Date.from(now.minusSeconds(7200)))
            .expiration(Date.from(now.minusSeconds(3600)))
            .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
            .compact();

    assertThatThrownBy(() -> jwt.parse(expired)).isInstanceOf(ExpiredJwtException.class);
  }
}
