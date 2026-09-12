package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtUtil;
import java.util.UUID;

/** Honors {@link TestAuthHelper#mockJwt} tokens through a mocked {@link JwtUtil} bean. */
final class MockSecurity {
  private MockSecurity() {}

  static void stubJwt(JwtUtil jwt) {
    when(jwt.parse(anyString()))
        .thenAnswer(
            invocation -> {
              String token = invocation.getArgument(0);
              String[] parts = token.split("\\.", 3);
              UUID userId = UUID.fromString(parts[1]);
              return new CurrentUser(userId, userId + "@example.com", parts[2]);
            });
  }

  static String bearer(UUID userId, String role) {
    return "Bearer " + TestAuthHelper.mockJwt(userId, role);
  }
}
