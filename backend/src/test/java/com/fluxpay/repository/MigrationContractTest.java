package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Fresh V001..V005 baseline contract; old-database upgrade history is intentionally dropped. */
class MigrationContractTest {
  private String resource(String name) throws IOException {
    try (var input = getClass().getResourceAsStream("/db/migration/" + name)) {
      assertThat(input).as(name).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
    }
  }

  @Test
  void transferCatalogueReplacesLegacyRoutes() throws IOException {
    String sql = resource("V003__routing_payments_and_quotes.sql");
    assertThat(sql)
        .contains("CREATE TABLE transfer_providers (")
        .contains("CREATE TABLE transfer_routes (")
        .contains("provider_id RAW(16) NOT NULL REFERENCES transfer_providers(id)")
        .contains("CREATE TABLE transfer_route_outcomes (")
        .contains("transfer_route_id RAW(16) NOT NULL REFERENCES transfer_routes(id)")
        .doesNotContain("CREATE TABLE payout_routes (")
        .doesNotContain("chk_payout_route_type")
        .contains("route_code VARCHAR2(50) NOT NULL UNIQUE")
        .contains("route_name VARCHAR2(100) NOT NULL")
        .contains("base_fee NUMBER(19,4) NOT NULL")
        .contains("fx_spread_percentage NUMBER(9,6) NOT NULL")
        .contains("estimated_minutes NUMBER(10) NOT NULL")
        .contains("configured_success_rate NUMBER(5,2) NOT NULL")
        .contains("active NUMBER(1) NOT NULL")
        .contains("version NUMBER(10) DEFAULT 0 NOT NULL")
        .contains("payment_id VARCHAR2(50) NOT NULL")
        .contains("transfer_route_id RAW(16) NOT NULL REFERENCES transfer_routes(id)")
        .contains("failure_reason VARCHAR2(1000)")
        .contains("provider_reference VARCHAR2(100) UNIQUE")
        .contains("CONSTRAINT uq_payout_attempt UNIQUE (payment_id, attempt_number)")
        .contains("status IN ('INITIATED', 'PROCESSING', 'COMPLETED', 'FAILED')");
    assertThat(sql)
        .doesNotContain("payout_routes_legacy")
        .doesNotContain("payout_attempts_legacy")
        .doesNotContain("LEGACY_UNCONFIGURED");
  }

  @Test
  void quotesReferenceRealRouteCodesAndPaymentsDeferSelectedQuoteFk() throws IOException {
    String sql = resource("V003__routing_payments_and_quotes.sql");
    assertThat(sql)
        .doesNotContain("REFERENCES payout_routes (route_code)")
        .contains("route_id RAW(16) NOT NULL REFERENCES transfer_routes(id)")
        .contains("route_code VARCHAR2(50) NOT NULL")
        .contains("provider_id RAW(16) NOT NULL REFERENCES transfer_providers(id)")
        .contains("effective_reliability NUMBER(9,6) NOT NULL")
        .contains("ranking_score NUMBER(19,12) NOT NULL")
        .contains(
            "CONSTRAINT uq_payment_quote_generation UNIQUE (payment_id, generation, route_id)")
        .contains(
            "ALTER TABLE payments ADD CONSTRAINT fk_payment_selected_quote FOREIGN KEY"
                + " (selected_quote_id) REFERENCES payment_quotes (id)");
    assertThat(sql.indexOf("CREATE TABLE payments ("))
        .isLessThan(sql.indexOf("CREATE TABLE payment_quotes ("));
    assertThat(sql.indexOf("CREATE TABLE payment_quotes ("))
        .isLessThan(sql.indexOf("ALTER TABLE payments ADD CONSTRAINT fk_payment_selected_quote"));
  }

  @Test
  void paymentEventsUseJsonPayloadAndChronologicalIndex() throws IOException {
    String sql = resource("V004__operations_outbox_and_events.sql");
    assertThat(sql)
        .contains("payment_id VARCHAR2(50) NOT NULL")
        .contains("event_payload CLOB NOT NULL")
        .contains("CONSTRAINT chk_payment_event_payload_json CHECK (event_payload IS JSON)")
        .contains("correlation_id VARCHAR2(100) NOT NULL")
        .contains("kafka_topic VARCHAR2(150) NOT NULL")
        .contains("occurred_at TIMESTAMP WITH TIME ZONE NOT NULL")
        .contains(
            "CREATE INDEX idx_payment_events_payment ON payment_events (payment_id, occurred_at)");
  }

  @Test
  void operationsExposePendingAndCompletedInvariants() throws IOException {
    String sql = resource("V004__operations_outbox_and_events.sql");
    assertThat(sql)
        .contains("CONSTRAINT chk_payment_operation_pending CHECK")
        .contains("outcome_status IS NULL AND response_data IS NULL")
        .contains("CONSTRAINT chk_payment_operation_completed CHECK")
        .contains("outcome_status IS NOT NULL AND outcome_status BETWEEN 200 AND 599")
        .contains(
            "CONSTRAINT chk_payment_operation_request_json CHECK (normalized_request IS JSON)")
        .contains(
            "CONSTRAINT chk_payment_operation_response_json CHECK"
                + " (response_data IS NULL OR response_data IS JSON)");
  }

  @Test
  void baselineUsesBusinessNamesOnly() throws IOException {
    for (String name :
        new String[] {
          "V001__identity_and_kyc.sql",
          "V002__wallets_and_ledger.sql",
          "V003__routing_payments_and_quotes.sql",
          "V004__operations_outbox_and_events.sql",
          "V005__compliance_policy_and_vectors.sql"
        }) {
      String sql = resource(name);
      assertThat(sql)
          .as(name)
          .doesNotContain("m1_")
          .doesNotContain("m2_")
          .doesNotContain("m3_")
          .doesNotContain("m4_");
      assertThat(sql).as(name).doesNotContain("m3_flow_version");
    }
    String operations = resource("V004__operations_outbox_and_events.sql");
    assertThat(operations)
        .contains("CREATE TABLE payment_operations (")
        .contains("CREATE TABLE review_decisions (")
        .contains("CREATE TABLE outbox_delivery (");
  }

  @Test
  void moneyKeepsSupportedPrecisionAndJsonValidity() throws IOException {
    String walletLedger = resource("V002__wallets_and_ledger.sql");
    assertThat(walletLedger)
        .contains("balance NUMBER(19,4)")
        .contains("held_balance NUMBER(19,4)")
        .contains("amount NUMBER(19,4)")
        .contains("CONSTRAINT chk_wallet_operation_response_json CHECK")
        .contains("response_snapshot IS NULL OR response_snapshot IS JSON");
    String routing = resource("V003__routing_payments_and_quotes.sql");
    assertThat(routing)
        .contains("amount NUMBER(19,4)")
        .contains("fee_amount NUMBER(19,4)")
        .contains("recipient_amount NUMBER(19,4)")
        .contains("market_rate NUMBER(19,6)")
        .contains("recipient_snapshot IS NULL OR recipient_snapshot IS JSON");
  }
}
