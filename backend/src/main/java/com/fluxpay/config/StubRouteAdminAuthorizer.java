package com.fluxpay.config;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.RouteAdminAuthorizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provisional M4 {@link RouteAdminAuthorizer} so this slice is independently demonstrable.
 *
 * <p>CHECKPOINT: M1 owns the production {@code RouteAdminAuthorizer} (JWT filter and role/owner
 * policy belong to M1/M3); this stub must be replaced when those members merge. Do not extend it
 * with role business rules.
 */
@Configuration(proxyBeanMethods = false)
public class StubRouteAdminAuthorizer {

  @Bean
  @ConditionalOnMissingBean(RouteAdminAuthorizer.class)
  RouteAdminAuthorizer routeAdminAuthorizer() {
    return new FallbackRouteAdminAuthorizer();
  }

  private static final class FallbackRouteAdminAuthorizer implements RouteAdminAuthorizer {

    @Override
    public boolean isAdmin(CurrentUser user) {
      return user != null && "ADMIN".equals(user.role());
    }

    @Override
    public boolean isOwner(CurrentUser user, PaymentSnapshot payment) {
      return user != null && payment.senderUserId().equals(user.userId());
    }
  }
}
