package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Opt-in regression for the real M5 pool/Oracle JDBC timestamp boundary. Only SELECTs and
 * connection-local ALTER SESSION statements are executed: no tables, rows, or migrations change.
 */
@EnabledIfEnvironmentVariable(named = "M5_ORACLE_TIMESTAMP_TESTS", matches = "true")
class M5OracleTimestampRoundTripTest {
  private static final Instant QUOTE_EXPIRY = Instant.parse("2026-09-14T17:13:22.916068Z");

  @Test
  void configuredPoolPreservesQuoteExpiryInANonUtcOracleEnvironment() throws Exception {
    try (var pool = pool(configuredInitializationSql()); var connection = pool.getConnection()) {
      var calendar = configuredJdbcCalendar(pool);
      try (var statement =
          connection.prepareStatement("SELECT CAST(? AS TIMESTAMP WITH TIME ZONE) FROM dual")) {
        statement.setTimestamp(1, Timestamp.from(QUOTE_EXPIRY), calendar);
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals(QUOTE_EXPIRY, result.getObject(1, OffsetDateTime.class).toInstant());
          assertEquals(QUOTE_EXPIRY, result.getTimestamp(1, calendar).toInstant());
        }
      }
    }
  }

  @Test
  void configuredPoolPreservesLegacyTimezoneLessInstants() throws Exception {
    try (var pool = pool(configuredInitializationSql()); var connection = pool.getConnection()) {
      var calendar = configuredJdbcCalendar(pool);
      try (var statement = connection.prepareStatement("SELECT CAST(? AS TIMESTAMP) FROM dual")) {
        statement.setTimestamp(1, Timestamp.from(QUOTE_EXPIRY), calendar);
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals(QUOTE_EXPIRY, result.getTimestamp(1, calendar).toInstant());
        }
      }
    }
  }

  @Test
  void everyPhysicalM5ConnectionUsesUtc() throws Exception {
    try (var pool = pool(configuredInitializationSql());
        var first = pool.getConnection();
        var second = pool.getConnection()) {
      for (var connection : new Connection[] {first, second}) {
        try (var statement = connection.createStatement();
            var result = statement.executeQuery("SELECT TZ_OFFSET(SESSIONTIMEZONE) FROM dual")) {
          assertTrue(result.next());
          assertEquals("+00:00", result.getString(1));
        }
      }
    }
  }

  @Test
  void nonUtcControlReproducesTheReportedFiveAndAHalfHourShift() throws Exception {
    try (var pool = pool("ALTER SESSION SET TIME_ZONE='+05:30'");
        var connection = pool.getConnection()) {
      var calendar = configuredJdbcCalendar(pool);
      try (var statement =
          connection.prepareStatement("SELECT CAST(? AS TIMESTAMP WITH TIME ZONE) FROM dual")) {
        statement.setTimestamp(1, Timestamp.from(QUOTE_EXPIRY), calendar);
        try (var result = statement.executeQuery()) {
          assertTrue(result.next());
          assertEquals(
              Instant.parse("2026-09-14T11:43:22.916068Z"),
              result.getObject(1, OffsetDateTime.class).toInstant());
        }
      }
    }
  }

  private static String configuredInitializationSql() throws Exception {
    return PropertiesLoaderUtils.loadProperties(new ClassPathResource("m5-backend.properties"))
        .getProperty("spring.datasource.hikari.connection-init-sql");
  }

  private static Calendar configuredJdbcCalendar(HikariDataSource pool) {
    var factory = new M5M3IntegrationConfiguration().entityManagerFactory(pool);
    String zone = factory.getJpaPropertyMap().get("hibernate.jdbc.time_zone").toString();
    return Calendar.getInstance(TimeZone.getTimeZone(zone));
  }

  private static HikariDataSource pool(String initializationSql) {
    String url = System.getenv("ORACLE_JDBC_URL");
    String username = System.getenv("ORACLE_USERNAME");
    assertEquals("jdbc:oracle:thin:@//localhost:1521/FREEPDB1", url);
    assertEquals("fluxpay", username.toLowerCase(java.util.Locale.ROOT));
    var delegate =
        new DriverManagerDataSource(url, username, System.getenv("ORACLE_PASSWORD"));
    var configuration = new HikariConfig();
    // Make the regression independent of the developer machine's timezone. Hikari must
    // apply the actual M5 initialization SQL after this deliberately non-UTC connection.
    configuration.setDataSource(
        new AbstractDataSource() {
          @Override
          public Connection getConnection() throws SQLException {
            var connection = delegate.getConnection();
            try {
              try (var statement = connection.createStatement()) {
                statement.execute("ALTER SESSION SET TIME_ZONE='+05:30'");
              }
              return connection;
            } catch (SQLException failure) {
              connection.close();
              throw failure;
            }
          }

          @Override
          public Connection getConnection(String user, String password) throws SQLException {
            throw new java.sql.SQLFeatureNotSupportedException("Use the scoped test connection");
          }
        });
    configuration.setMaximumPoolSize(2);
    configuration.setMinimumIdle(0);
    configuration.setConnectionTimeout(5000);
    configuration.setInitializationFailTimeout(5000);
    configuration.setConnectionInitSql(initializationSql);
    return new HikariDataSource(configuration);
  }
}
