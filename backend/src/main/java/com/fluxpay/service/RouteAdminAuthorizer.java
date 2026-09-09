package com.fluxpay.service;

import com.fluxpay.common.security.CurrentUser;

public interface RouteAdminAuthorizer {
  boolean isAdmin(CurrentUser user);

  boolean isOwner(CurrentUser user, PaymentSnapshot payment);
}
