package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class MigrationContractTest {
  private String resource(String name) throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
      assertThat(input).as(name).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }
  }

  @Test
  void routesAndAttemptsUseThePrdColumnsAndConstraints() throws IOException {
    String sql = resource("V401__m4_routes_attempts.sql");
    assertThat(sql)
        .contains("id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY")
        .contains("route_code VARCHAR2(50) NOT NULL UNIQUE")
        .contains("route_name VARCHAR2(100) NOT NULL")
        .contains("provider_name VARCHAR2(100) NOT NULL")
        .contains("route_type VARCHAR2(30) NOT NULL")
        .contains("base_fee NUMBER(19,4) NOT NULL CHECK (base_fee >= 0)")
        .contains("fx_spread_percentage NUMBER(9,6) NOT NULL")
        .contains("estimated_minutes NUMBER(10) NOT NULL")
        .contains("success_rate NUMBER(5,2) NOT NULL")
        .contains("active NUMBER(1) NOT NULL")
        .contains("version NUMBER(10) DEFAULT 0 NOT NULL")
        .contains("payment_id VARCHAR2(50) NOT NULL")
        .contains("payout_route_id RAW(16) NOT NULL REFERENCES payout_routes(id)")
        .contains("failure_reason VARCHAR2(1000)")
        .contains("provider_reference VARCHAR2(100) UNIQUE")
        .contains("CONSTRAINT uq_payout_attempt UNIQUE (payment_id, attempt_number)")
        .contains("'STANDARD_BANK'", "'INSTANT_PAYOUT'", "'LOCAL_PARTNER'");
    assertThat(sql).doesNotContain("REFERENCES payments");
  }

  @Test
  void paymentEventsUseJsonPayloadAndChronologicalIndex() throws IOException {
    String sql = resource("V402__m4_payment_events.sql");
    assertThat(sql)
        .contains("id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY")
        .contains("payment_id VARCHAR2(50) NOT NULL")
        .contains("event_payload CLOB NOT NULL CHECK (event_payload IS JSON)")
        .contains("correlation_id VARCHAR2(100) NOT NULL")
        .contains("kafka_topic VARCHAR2(150) NOT NULL")
        .contains("occurred_at TIMESTAMP WITH TIME ZONE NOT NULL")
        .contains("CREATE INDEX idx_events_payment ON payment_events(payment_id, occurred_at)");
  }
}
