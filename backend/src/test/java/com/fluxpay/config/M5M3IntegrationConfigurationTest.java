package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class M5M3IntegrationConfigurationTest {
  @Test
  void mapsJavaInstantsToOracleTimestampWithoutRequestingATimeZone() {
    var factory =
        new M5M3IntegrationConfiguration().entityManagerFactory(mock(DataSource.class));

    assertEquals(
        "TIMESTAMP",
        factory.getJpaPropertyMap().get("hibernate.type.preferred_instant_jdbc_type"));
  }
}
