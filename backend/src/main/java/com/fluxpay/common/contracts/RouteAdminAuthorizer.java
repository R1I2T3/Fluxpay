package com.fluxpay.common.contracts;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.service.PaymentSnapshot;

public interface RouteAdminAuthorizer {
  boolean isAdmin(CurrentUser user);

  boolean isOwner(CurrentUser user, PaymentSnapshot payment);
}
