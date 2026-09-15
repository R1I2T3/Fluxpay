package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Hibernate validation against the fresh V001..V005 baseline on the isolated FLUXPAY_TEST schema.
 * Boots the full JPA mapping with {@code ddl-auto=validate} after Flyway migration; no schema is
 * created or reset here beyond the isolated test schema's own migration.
 */
@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS_ACTIVE", matches = "true")
@SpringBootTest(
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.jdbc.time_zone=UTC"
    })
class HibernateValidationOracleTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) throws Exception {
    FreshBaselineOracleTest.migrateIsolatedSchema();
    properties.add("spring.datasource.url", () -> System.getenv("ORACLE_TEST_JDBC_URL"));
    properties.add("spring.datasource.username", () -> System.getenv("ORACLE_TEST_USERNAME"));
    properties.add("spring.datasource.password", () -> System.getenv("ORACLE_TEST_PASSWORD"));
    properties.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
  }

  @Autowired private org.springframework.context.ApplicationContext context;

  @Test
  void fullJpaMappingValidatesAgainstFreshBaseline() {
    assertThat(context.getBean(jakarta.persistence.EntityManagerFactory.class)).isNotNull();
  }
}
