package com.fluxpay.service;

import com.fluxpay.common.security.CurrentUser;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class PersistentRouteAdminAuthorizer implements RouteAdminAuthorizer {
  @Override
  public boolean isAdmin(CurrentUser user) {
    return user != null && "ADMIN".equals(user.role());
  }

  @Override
  public boolean isOwner(CurrentUser user, PaymentSnapshot payment) {
    return user != null && payment != null && Objects.equals(payment.senderUserId(), user.userId());
  }
}
