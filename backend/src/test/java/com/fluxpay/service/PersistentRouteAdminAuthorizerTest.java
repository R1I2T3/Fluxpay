package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fluxpay.common.security.CurrentUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersistentRouteAdminAuthorizerTest {
  @Test
  void adminAndOwnerRules() {
    PersistentRouteAdminAuthorizer auth = new PersistentRouteAdminAuthorizer();
    UUID owner = UUID.randomUUID();
    PaymentSnapshot payment =
        new PaymentSnapshot(
            UUID.randomUUID().toString(),
            owner,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new java.math.BigDecimal("10.00"),
            "USD",
            "INR",
            com.fluxpay.common.enums.PaymentStatus.ROUTED);
    assertTrue(auth.isAdmin(new CurrentUser(UUID.randomUUID(), "a@x.com", "ADMIN")));
    assertFalse(auth.isAdmin(new CurrentUser(owner, "u@x.com", "USER")));
    assertTrue(auth.isOwner(new CurrentUser(owner, "u@x.com", "USER"), payment));
  }
}
