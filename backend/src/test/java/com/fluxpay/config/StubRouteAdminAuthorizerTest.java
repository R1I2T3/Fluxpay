package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.service.RouteAdminAuthorizer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StubRouteAdminAuthorizerTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(StubRouteAdminAuthorizer.class);

  @Test
  void providesTheFallbackAuthorizerWhenNoProductionBeanExists() {
    contextRunner.run(context -> assertThat(context).hasSingleBean(RouteAdminAuthorizer.class));
  }
}
