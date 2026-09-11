package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class M5SoloSafetyUnitTest {
  private void check(Map<String, String> env, String actualSchema, String actualUser) {
    try {
      Class.forName("com.fluxpay.config.M5SoloFixtureSetup")
          .getMethod("verifyDeclaration", Map.class, String.class, String.class)
          .invoke(null, env, actualSchema, actualUser);
    } catch (InvocationTargetException failure) {
      throw (RuntimeException) failure.getCause();
    } catch (ReflectiveOperationException missing) {
      fail("The pre-Flyway schema safety gate has not been implemented");
    }
  }

  @Test void refusesSetupBeforeMigrationsWithoutExactExplicitSchemaAgreement() {
    var env = new HashMap<>(Map.of("M5_ALLOW_FIXTURE_SETUP", "true", "M5_TEST_SCHEMA", "FLUXPAY_M5_TEST",
        "ORACLE_USERNAME", "FLUXPAY_M5_TEST"));
    assertThrows(IllegalStateException.class, () -> check(Map.of(), "FLUXPAY", "FLUXPAY"));
    assertThrows(IllegalStateException.class, () -> check(env, "FLUXPAY", "FLUXPAY_M5_TEST"));
    assertThrows(IllegalStateException.class, () -> check(env, "FLUXPAY_M5_TEST", "FLUXPAY"));
    env.put("M5_ALLOW_FIXTURE_SETUP", "false");
    assertThrows(IllegalStateException.class, () -> check(env, "FLUXPAY_M5_TEST", "FLUXPAY_M5_TEST"));
  }

  @Test void admitsOnlyExplicitDedicatedUnquotedSchema() {
    var env = Map.of("M5_ALLOW_FIXTURE_SETUP", "true", "M5_TEST_SCHEMA", "FLUXPAY_M5_TEST", "ORACLE_USERNAME", "FLUXPAY_M5_TEST");
    assertDoesNotThrow(() -> check(env, "FLUXPAY_M5_TEST", "FLUXPAY_M5_TEST"));
    assertThrows(IllegalStateException.class, () -> check(Map.of("M5_ALLOW_FIXTURE_SETUP", "true", "M5_TEST_SCHEMA", "SYSTEM", "ORACLE_USERNAME", "SYSTEM"), "SYSTEM", "SYSTEM"));
  }
}
