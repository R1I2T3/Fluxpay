package com.fluxpay.common;
import com.fluxpay.common.security.CurrentUser;
import java.util.UUID;
public final class TestAuthHelper {
  private TestAuthHelper() {}
  public static String mockJwt(UUID userId, String role) { return "mock." + userId + "." + role; }
  public static CurrentUser withUser(UUID userId, String email, String role) { return new CurrentUser(userId, email, role); }
  public static CurrentUser withUser(String email, String role) { return new CurrentUser(UUID.randomUUID(), email, role); }
}
