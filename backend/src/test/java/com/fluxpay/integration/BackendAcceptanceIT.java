package com.fluxpay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.FluxPayApplication;
import com.fluxpay.beans.User;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.FxSnapshotSource;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.messaging.EventTopics;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentEventRepository;
import com.fluxpay.repository.UserRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@EnabledIfEnvironmentVariable(named = "ORACLE_TESTS_ACTIVE", matches = "(?i)true")
@SpringBootTest(
    classes = {FluxPayApplication.class, BackendAcceptanceIT.TestIntegrations.class},
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("oracle-it")
@TestPropertySource(
    properties = {
      "fluxpay.system-user-id=00000000-0000-0000-0000-000000000001",
      "fluxpay.demo-funding-enabled=true",
      "fluxpay.development.kyc-metadata-enabled=true",
      "fluxpay.development.simulated-compliance-enabled=true",
      "fluxpay.development.simulated-payouts-enabled=false",
      "fluxpay.outbox.dispatch-delay-ms=100",
      "fluxpay.outbox.dispatch-initial-delay-ms=100",
      "fluxpay.kafka.timeline-group=fluxpay-backend-acceptance-${random.uuid}"
    })
/**
 * Acceptance coverage for the admin-managed transfer catalogue. The catalogue is provisioned
 * through the admin provider/route APIs: HDFC Bank and SBI share one {@code BANK_NETWORK} rail,
 * HDFC owns the standard and express routes, and quote generation persists exactly the top three
 * eligible routes. Terminal payout attempts teach the shared catalogue learned reliability, and
 * wallet-to-wallet transfers resolve their own single-call internal route decision.
 */
class BackendAcceptanceIT {
  private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private WebApplicationContext webContext;
  @Autowired private ObjectMapper json;
  @Autowired private UserRepository users;
  @Autowired private WalletRepository wallets;
  @Autowired private BCryptPasswordEncoder passwords;
  @Autowired private JwtUtil jwt;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private Flyway flyway;
  @Autowired private OutboxEventRepository outboxEvents;
  @Autowired private OutboxDeliveryRepository deliveries;
  @Autowired private PaymentEventRepository paymentEvents;

  private MockMvc mvc;
  private String adminToken;

  @BeforeEach
  void provisionAcceptanceFixtures() {
    mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    provisionSystemAccounts();
    adminToken = provisionAdmin();
  }

  @Test
  void adminManagedCatalogueRoutesExternalAndInternalTransfers() throws Exception {
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().applied())
        .anyMatch(
            migration ->
                migration.getVersion() != null
                    && "006".equals(migration.getVersion().getVersion()));
    Catalogue catalogue = provisionCatalogue();

    mvc.perform(get("/api/wallets").header("X-Local-User-Id", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());

    String email = "acceptance-" + UUID.randomUUID() + "@example.com";
    String password = "AcceptancePass123!";
    request(
        post("/api/auth/register"),
        null,
        null,
        Map.of("email", email, "password", password, "fullName", "Acceptance Customer"),
        201);
    JsonNode login =
        request(
            post("/api/auth/login"), null, null, Map.of("email", email, "password", password), 200);
    String customerToken = login.path("data").path("token").asText();

    JsonNode internalPeer = register("acceptance-peer");
    assertInternalTransfer(customerToken, internalPeer.path("email").asText(), catalogue);

    JsonNode kyc =
        request(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                    "/api/kyc/applications")
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "files", "acceptance.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes()))
                .param("docType", "PASSPORT")
                .param("docNumber", "ACCEPTANCE-" + UUID.randomUUID()),
            customerToken,
            null,
            null,
            201);
    String applicationId = kyc.path("data").path("applicationId").asText();
    long kycVersion = kyc.path("data").path("version").asLong();
    request(
        put("/api/admin/kyc/applications/" + applicationId + "/approve"),
        adminToken,
        null,
        Map.of("expectedVersion", kycVersion),
        200);

    request(
        post("/api/wallets/receive-demo"),
        customerToken,
        "acceptance-fund-" + UUID.randomUUID(),
        Map.of("currency", "USD", "amount", "2000.0000"),
        200);
    JsonNode walletList = request(get("/api/wallets"), customerToken, null, null, 200);
    String sourceWallet =
        find(walletList.path("data"), "currency", "USD").path("walletId").asText();

    JsonNode recipient =
        request(
            post("/api/recipients"),
            customerToken,
            null,
            Map.of(
                "name", "Acceptance Recipient",
                "account", "ACCT-" + UUID.randomUUID(),
                "bankName", "Acceptance Bank",
                "country", "IN",
                "currency", "INR",
                "status", "ACTIVE"),
            201);
    String recipientId = recipient.path("data").path("id").asText();

    assertCatalogueQuoteSet(customerToken, sourceWallet, recipientId, catalogue);

    PaymentFlow successful = createAndConfirm(customerToken, sourceWallet, recipientId, "100.0000");
    String successKey = "acceptance-payout-success-" + UUID.randomUUID();
    JsonNode payout =
        request(
            post("/api/payments/" + successful.paymentId + "/submit-payout"),
            customerToken,
            successKey,
            Map.of("routeCode", "HDFC_INR_STANDARD"),
            200);
    assertThat(payout.path("data").path("status").asText()).isEqualTo("COMPLETED");
    String completedEventId = payout.path("data").path("originalEventId").asText();
    JsonNode payoutReplay =
        request(
            post("/api/payments/" + successful.paymentId + "/submit-payout"),
            customerToken,
            successKey,
            Map.of("routeCode", "HDFC_INR_STANDARD"),
            200);
    assertThat(payoutReplay.path("data").path("originalEventId").asText())
        .isEqualTo(completedEventId);
    assertJournalEntryCount(successful.paymentId, 3);

    PaymentFlow retried = createAndConfirm(customerToken, sourceWallet, recipientId, "210.0000");
    JsonNode failedRetryable =
        submit(customerToken, retried.paymentId, "HDFC_INR_STANDARD", "retryable-first");
    assertThat(failedRetryable.path("data").path("status").asText()).isEqualTo("FAILED");
    JsonNode retriedResult =
        request(
            post("/api/payments/" + retried.paymentId + "/retry-payout"),
            customerToken,
            "acceptance-retry-" + UUID.randomUUID(),
            Map.of(),
            200);
    assertThat(retriedResult.path("data").path("status").asText()).isEqualTo("COMPLETED");
    assertJournalEntryCount(retried.paymentId, 3);

    PaymentFlow switched = createAndConfirm(customerToken, sourceWallet, recipientId, "220.0000");
    assertThat(
            submit(customerToken, switched.paymentId, "HDFC_INR_STANDARD", "switch-first")
                .path("data")
                .path("status")
                .asText())
        .isEqualTo("FAILED");
    JsonNode switchedResult =
        request(
            post("/api/payments/" + switched.paymentId + "/switch-route"),
            customerToken,
            "acceptance-switch-" + UUID.randomUUID(),
            Map.of("routeCode", "SBI_INR_STANDARD", "quoteId", switched.alternateQuoteId),
            200);
    assertThat(switchedResult.path("data").path("status").asText()).isEqualTo("COMPLETED");
    assertThat(switchedResult.path("data").path("selectedQuote").path("routeCode").asText())
        .isEqualTo("SBI_INR_STANDARD");

    PaymentFlow refunded = createAndConfirm(customerToken, sourceWallet, recipientId, "230.0000");
    assertThat(
            submit(customerToken, refunded.paymentId, "HDFC_INR_STANDARD", "refund-first")
                .path("data")
                .path("status")
                .asText())
        .isEqualTo("FAILED");
    String refundKey = "acceptance-refund-" + UUID.randomUUID();
    JsonNode refund =
        request(
            post("/api/admin/payments/" + refunded.paymentId + "/refund"),
            adminToken,
            refundKey,
            Map.of(),
            200);
    JsonNode refundReplay =
        request(
            post("/api/admin/payments/" + refunded.paymentId + "/refund"),
            adminToken,
            refundKey,
            Map.of(),
            200);
    assertThat(refundReplay.path("data").path("eventId").asText())
        .isEqualTo(refund.path("data").path("eventId").asText());
    assertExactRefund(refunded.paymentId, "230.0000", "225.0000", "5.0000");

    assertLearnedReliability();

    awaitTimeline(refunded.paymentId, EventTopics.PAYMENT_REFUNDED);
    JsonNode timeline =
        request(
            get("/api/payments/" + refunded.paymentId + "/timeline"),
            customerToken,
            null,
            null,
            200);
    assertThat(timeline.path("data").isArray()).isTrue();
    assertThat(timeline.path("data").toString())
        .contains(refund.path("data").path("eventId").asText());

    assertCanonicalOutbox(successful.paymentId);
    assertThat(
            deliveries.findByPaymentIdOrderByAggregateSequenceAsc(
                UUID.fromString(successful.paymentId)))
        .allMatch(delivery -> delivery.aggregateSequence() > 0);
  }

  private PaymentFlow createAndConfirm(
      String token, String sourceWallet, String recipientId, String amount) throws Exception {
    JsonNode draft =
        request(
            post("/api/payments/draft"),
            token,
            "acceptance-draft-" + UUID.randomUUID(),
            Map.of(
                "sourceWalletId", sourceWallet,
                "recipientId", recipientId,
                "sourceAmount", amount,
                "sourceCurrency", "USD",
                "payoutCurrency", "INR",
                "purpose", "FAMILY_SUPPORT",
                "preference", "BALANCED"),
            201);
    String paymentId = draft.path("data").path("id").asText();
    JsonNode quote =
        request(
            post("/api/payments/" + paymentId + "/quotes"),
            token,
            "acceptance-quote-" + UUID.randomUUID(),
            Map.of(),
            201);
    assertThat(quote.path("data").path("quotes").size()).isEqualTo(3);
    JsonNode standard = find(quote.path("data").path("quotes"), "route", "HDFC_INR_STANDARD");
    JsonNode alternate = find(quote.path("data").path("quotes"), "route", "SBI_INR_STANDARD");
    JsonNode confirmed =
        request(
            post("/api/payments/" + paymentId + "/confirm"),
            token,
            "acceptance-confirm-" + UUID.randomUUID(),
            Map.of("quoteId", standard.path("id").asText()),
            200);
    assertThat(confirmed.path("data").path("status").asText()).isEqualTo("PROCESSING");
    return new PaymentFlow(paymentId, alternate.path("id").asText());
  }

  private JsonNode submit(String token, String paymentId, String route, String suffix)
      throws Exception {
    return request(
        post("/api/payments/" + paymentId + "/submit-payout"),
        token,
        "acceptance-" + suffix + "-" + UUID.randomUUID(),
        Map.of("routeCode", route),
        200);
  }

  private JsonNode request(
      MockHttpServletRequestBuilder builder, String token, String key, Object body, int expected)
      throws Exception {
    builder.accept(MediaType.APPLICATION_JSON);
    if (token != null) {
      builder.header("Authorization", "Bearer " + token);
    }
    if (key != null) {
      builder.header("Idempotency-Key", key);
    }
    if (body != null) {
      builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
    }
    return json.readTree(
        mvc.perform(builder)
            .andExpect(status().is(expected))
            .andReturn()
            .getResponse()
            .getContentAsByteArray());
  }

  private static JsonNode find(JsonNode array, String field, String value) {
    for (JsonNode item : array) {
      if (value.equals(item.path(field).asText())) {
        return item;
      }
    }
    throw new AssertionError("No item with " + field + "=" + value + " in " + array);
  }

  private void provisionSystemAccounts() {
    if (users.findById(SYSTEM_USER).isEmpty()) {
      Instant now = Instant.now();
      users.saveAndFlush(
          new User(
              SYSTEM_USER,
              "system@acceptance.invalid",
              passwords.encode("SystemAcceptancePass123!"),
              "SYSTEM",
              "Acceptance System",
              now,
              now));
    }
    for (String currency : java.util.List.of("USD", "EUR", "INR")) {
      for (WalletAccountRole role :
          java.util.List.of(
              WalletAccountRole.FX_CLEARING,
              WalletAccountRole.DEMO_CLEARING,
              WalletAccountRole.PAYOUT_CLEARING,
              WalletAccountRole.FEE_REVENUE)) {
        if (wallets.findByUserIdAndCurrencyAndAccountRole(SYSTEM_USER, currency, role).isEmpty()) {
          wallets.saveAndFlush(new Wallet(SYSTEM_USER, currency, role));
        }
      }
    }
  }

  /**
   * Acceptance catalogue identifiers behind each quoted route. Both banks share one bank-network
   * rail; HDFC owns the standard and express routes.
   */
  private record Catalogue(
      String hdfcProviderId,
      String sbiProviderId,
      String hdfcStandardRouteId,
      String hdfcExpressRouteId,
      String sbiStandardRouteId,
      String sbiEconomyRouteId) {}

  private Catalogue provisionCatalogue() throws Exception {
    JsonNode hdfc = ensureProvider("HDFC_BANK", "HDFC Bank", "BANK_NETWORK");
    JsonNode sbi = ensureProvider("SBI_BANK", "SBI", "BANK_NETWORK");
    JsonNode fluxpay = ensureProvider("FLUXPAY", "FluxPay", "INTERNAL_LEDGER");
    assertThat(hdfc.path("railType").asText()).isEqualTo("BANK_NETWORK");
    assertThat(sbi.path("railType").asText()).isEqualTo("BANK_NETWORK");
    assertThat(fluxpay.path("railType").asText()).isEqualTo("INTERNAL_LEDGER");

    JsonNode standard =
        ensureRoute(
            hdfc.path("id").asText(),
            routeBody("HDFC_INR_STANDARD", "HDFC INR Standard", "1.0000", "0.500000", 60, "99.00"));
    JsonNode express =
        ensureRoute(
            hdfc.path("id").asText(),
            routeBody("HDFC_INR_EXPRESS", "HDFC INR Express", "6.0000", "0.500000", 5, "98.50"));
    JsonNode sbiStandard =
        ensureRoute(
            sbi.path("id").asText(),
            routeBody("SBI_INR_STANDARD", "SBI INR Standard", "5.0000", "0.500000", 90, "99.50"));
    JsonNode sbiEconomy =
        ensureRoute(
            sbi.path("id").asText(),
            routeBody("SBI_INR_ECONOMY", "SBI INR Economy", "4.0000", "1.500000", 600, "95.00"));
    ensureRoute(
        fluxpay.path("id").asText(),
        internalRouteBody("FLUXPAY_INTERNAL", "FluxPay internal wallet"));
    return new Catalogue(
        hdfc.path("id").asText(),
        sbi.path("id").asText(),
        standard.path("id").asText(),
        express.path("id").asText(),
        sbiStandard.path("id").asText(),
        sbiEconomy.path("id").asText());
  }

  private JsonNode ensureProvider(String code, String name, String railType) throws Exception {
    JsonNode list = request(get("/api/admin/providers"), adminToken, null, null, 200);
    for (JsonNode provider : list.path("data").path("providers")) {
      if (code.equals(provider.path("providerCode").asText())) {
        return provider;
      }
    }
    return request(
            post("/api/admin/providers"),
            adminToken,
            null,
            Map.of(
                "providerCode", code, "providerName", name, "railType", railType, "active", true),
            201)
        .path("data");
  }

  /**
   * Builds an external IN/INR route body for the given acceptance route identity; the caller
   * supplies the owning provider id.
   */
  private Map<String, Object> routeBody(
      String code, String name, String fee, String spread, int minutes, String reliability) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("routeCode", code);
    body.put("name", name);
    body.put("destinationType", "EXTERNAL_ACCOUNT");
    body.put("destinationCountry", "IN");
    body.put("payoutCurrency", "INR");
    body.put("baseFee", fee);
    body.put("fxSpreadPercentage", spread);
    body.put("estimatedMinutes", minutes);
    body.put("configuredSuccessRate", reliability);
    body.put("active", true);
    return body;
  }

  private Map<String, Object> internalRouteBody(String code, String name) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("routeCode", code);
    body.put("name", name);
    body.put("destinationType", "INTERNAL_WALLET");
    body.put("payoutCurrency", "INR");
    body.put("baseFee", "0.0000");
    body.put("fxSpreadPercentage", "0.000000");
    body.put("estimatedMinutes", 1);
    body.put("configuredSuccessRate", "100.00");
    body.put("active", true);
    return body;
  }

  private JsonNode ensureRoute(String providerId, Map<String, Object> body) throws Exception {
    JsonNode list = request(get("/api/admin/routes"), adminToken, null, null, 200);
    String code = String.valueOf(body.get("routeCode"));
    for (JsonNode route : list.path("data").path("routes")) {
      if (code.equals(route.path("routeCode").asText())) {
        return route;
      }
    }
    Map<String, Object> create = new LinkedHashMap<>(body);
    create.put("providerId", providerId);
    return request(post("/api/admin/routes"), adminToken, null, create, 201).path("data");
  }

  /**
   * The shared bank rail rejects only the HDFC provider's configured failure probes; SBI always
   * completes. Four eligible external routes therefore rank to exactly three persisted quotes: the
   * two HDFC routes plus SBI standard.
   */
  private void assertCatalogueQuoteSet(
      String token, String sourceWallet, String recipientId, Catalogue catalogue) throws Exception {
    JsonNode draft =
        request(
            post("/api/payments/draft"),
            token,
            "acceptance-catalogue-draft-" + UUID.randomUUID(),
            Map.of(
                "sourceWalletId", sourceWallet,
                "recipientId", recipientId,
                "sourceAmount", "100.0000",
                "sourceCurrency", "USD",
                "payoutCurrency", "INR",
                "purpose", "FAMILY_SUPPORT",
                "preference", "BALANCED"),
            201);
    JsonNode quotes =
        request(
            post("/api/payments/" + draft.path("data").path("id").asText() + "/quotes"),
            token,
            "acceptance-catalogue-quotes-" + UUID.randomUUID(),
            Map.of(),
            201);
    JsonNode rows = quotes.path("data").path("quotes");
    assertThat(rows.size()).isEqualTo(3);
    List<String> codes = new ArrayList<>();
    List<String> providerIds = new ArrayList<>();
    for (JsonNode quote : rows) {
      codes.add(quote.path("routeCode").asText());
      providerIds.add(quote.path("providerId").asText());
    }
    assertThat(codes)
        .containsExactlyInAnyOrder("HDFC_INR_STANDARD", "HDFC_INR_EXPRESS", "SBI_INR_STANDARD");
    assertThat(providerIds.stream().filter(catalogue.hdfcProviderId()::equals).count())
        .isEqualTo(2);
    assertThat(providerIds).contains(catalogue.sbiProviderId());
  }

  /**
   * Terminal attempts teach the catalogue: the HDFC standard route failed and completed, so its
   * learned reliability sits below its configured prior with matching outcome counts.
   */
  private void assertLearnedReliability() throws Exception {
    JsonNode list = request(get("/api/admin/routes"), adminToken, null, null, 200);
    JsonNode learned = find(list.path("data").path("routes"), "routeCode", "HDFC_INR_STANDARD");
    assertThat(learned.path("failedCount").asLong()).isGreaterThanOrEqualTo(3);
    assertThat(learned.path("completedCount").asLong()).isGreaterThanOrEqualTo(3);
    assertThat(new BigDecimal(learned.path("effectiveSuccessRate").asText()))
        .isLessThan(new BigDecimal(learned.path("configuredSuccessRate").asText()));
  }

  /**
   * A wallet-to-wallet transfer is a single call that ranks internal candidates with the BALANCED
   * preference, executes the winner once through the internal ledger rail, and returns its routing
   * metadata. Replaying the idempotency key returns the same recorded transfer without debiting the
   * sender twice.
   */
  private void assertInternalTransfer(String token, String recipientEmail, Catalogue catalogue)
      throws Exception {
    JsonNode walletsBefore = request(get("/api/wallets"), token, null, null, 200);
    BigDecimal balanceBefore =
        new BigDecimal(
            find(walletsBefore.path("data"), "currency", "USD").path("availableBalance").asText());

    String key = "acceptance-internal-" + UUID.randomUUID();
    JsonNode first =
        request(
            post("/api/wallets/transfer"),
            token,
            key,
            Map.of(
                "toEmail", recipientEmail,
                "fromCurrency", "USD",
                "toCurrency", "INR",
                "amount", "10.00",
                "amountMode", "SOURCE",
                "note", "Acceptance wallet transfer"),
            200);
    assertThat(first.path("data").path("providerCode").asText()).isEqualTo("FLUXPAY");
    assertThat(first.path("data").path("routeCode").asText()).isEqualTo("FLUXPAY_INTERNAL");
    assertThat(first.path("data").path("railType").asText()).isEqualTo("INTERNAL_LEDGER");
    assertThat(first.path("data").path("journalReference").asText()).isNotBlank();
    assertThat(first.path("data").path("sourceAmount").asText()).isEqualTo("10.0000");
    assertThat(first.path("data").path("fee").asText()).isEqualTo("0.0500");
    assertThat(first.path("data").path("creditedAmount").asText()).isEqualTo("830.8250");
    assertThat(first.path("data").path("effectiveReliability").isNull()).isFalse();

    JsonNode replay =
        request(
            post("/api/wallets/transfer"),
            token,
            key,
            Map.of(
                "toEmail", recipientEmail,
                "fromCurrency", "USD",
                "toCurrency", "INR",
                "amount", "10.00",
                "amountMode", "SOURCE",
                "note", "Acceptance wallet transfer"),
            200);
    assertThat(replay.path("data").path("journalReference").asText())
        .isEqualTo(first.path("data").path("journalReference").asText());

    JsonNode walletsAfter = request(get("/api/wallets"), token, null, null, 200);
    BigDecimal balanceAfter =
        new BigDecimal(
            find(walletsAfter.path("data"), "currency", "USD").path("availableBalance").asText());
    assertThat(balanceBefore.subtract(balanceAfter)).isEqualByComparingTo("10.0000");

    Integer journalEntries =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE journal_reference = ?",
            Integer.class,
            first.path("data").path("journalReference").asText());
    assertThat(journalEntries).isEqualTo(5);
    assertThat(catalogue.hdfcProviderId()).isNotBlank();
  }

  /** Registers a second acceptance customer and returns its email plus bearer token. */
  private JsonNode register(String prefix) throws Exception {
    String email = prefix + "-" + UUID.randomUUID() + "@example.com";
    String password = "AcceptancePass123!";
    request(
        post("/api/auth/register"),
        null,
        null,
        Map.of("email", email, "password", password, "fullName", "Acceptance Customer"),
        201);
    JsonNode login =
        request(
            post("/api/auth/login"), null, null, Map.of("email", email, "password", password), 200);
    com.fasterxml.jackson.databind.node.ObjectNode response = json.createObjectNode();
    response.put("email", email);
    response.put("token", login.path("data").path("token").asText());
    return response;
  }

  private String provisionAdmin() {
    UUID adminId = UUID.randomUUID();
    String email = "acceptance-admin-" + adminId + "@example.com";
    Instant now = Instant.now();
    users.saveAndFlush(
        new User(
            adminId,
            email,
            passwords.encode("AdminAcceptancePass123!"),
            "ADMIN",
            "Acceptance Administrator",
            now,
            now));
    return jwt.generate(adminId, email, "ADMIN");
  }

  private void assertJournalEntryCount(String paymentId, int expected) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE journal_reference = ?",
            Integer.class,
            "payment:" + paymentId);
    assertThat(count).isEqualTo(expected);
  }

  private void assertExactRefund(String paymentId, String gross, String net, String fee) {
    String prefix = "refund:" + paymentId;
    assertThat(amount(prefix + ":sender:credit")).isEqualByComparingTo(gross);
    assertThat(amount(prefix + ":clearing:debit")).isEqualByComparingTo(net);
    assertThat(amount(prefix + ":fee:debit")).isEqualByComparingTo(fee);
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE journal_reference = ?",
            Integer.class,
            prefix);
    assertThat(count).isEqualTo(3);
  }

  private BigDecimal amount(String idempotencyKey) {
    return jdbc.queryForObject(
        "SELECT amount FROM ledger_entries WHERE idempotency_key = ?",
        BigDecimal.class,
        idempotencyKey);
  }

  private void awaitTimeline(String paymentId, String eventType) throws InterruptedException {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (paymentEvents.existsByPaymentIdAndEventType(paymentId, eventType)) {
        return;
      }
      Thread.sleep(200);
    }
    throw new AssertionError("timeline did not persist " + eventType + " for " + paymentId);
  }

  private void assertCanonicalOutbox(String paymentId) throws Exception {
    boolean found = false;
    for (var event : outboxEvents.findAll()) {
      JsonNode envelope = json.readTree(event.payload());
      if (!paymentId.equals(envelope.path("paymentId").asText())) {
        continue;
      }
      found = true;
      assertThat(envelope.path("eventId").asText()).isEqualTo(event.id().toString());
      assertThat(envelope.path("eventType").asText()).isEqualTo(event.topic());
      assertThat(envelope.path("payload").path("schemaVersion").asInt()).isEqualTo(1);
      assertThat(envelope.path("payload").path("aggregateSequence").asInt()).isPositive();
    }
    assertThat(found).isTrue();
  }

  private record PaymentFlow(String paymentId, String alternateQuoteId) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class TestIntegrations {
    @Bean
    @Primary
    FxSnapshotSource acceptanceFxSource() {
      return (from, to) ->
          new FxSnapshot(from, to, new BigDecimal("83.500000"), Instant.now(), false);
    }

    @Bean
    TransferRail acceptanceBankRail() {
      return new TransferRail() {
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        @Override
        public RailType type() {
          return RailType.BANK_NETWORK;
        }

        @Override
        public java.util.Set<DestinationType> supportedDestinations() {
          return java.util.Set.of(DestinationType.EXTERNAL_ACCOUNT);
        }

        @Override
        public TransferRailResult execute(TransferRailCommand command) {
          int attempt =
              attempts
                  .computeIfAbsent(command.transferId().toString(), ignored -> new AtomicInteger())
                  .incrementAndGet();
          BigDecimal amount = command.sourceAmount();
          // Only the HDFC provider rejects configured failure probes; every other acceptance
          // route shares this bank rail and always completes, which proves that two providers
          // and several routes can run on one rail implementation.
          boolean hdfc = "HDFC_BANK".equals(command.provider().code());
          boolean failsOnce =
              hdfc && amount.compareTo(new BigDecimal("210.0000")) == 0 && attempt == 1;
          boolean alwaysFails = hdfc && amount.compareTo(new BigDecimal("220.0000")) >= 0;
          if (failsOnce || alwaysFails) {
            return TransferRailResult.failed(
                "TEST_PROVIDER_REJECTED",
                "Acceptance provider rejected payout",
                command.customerFee());
          }
          return TransferRailResult.completed(
              "ACCEPTANCE-" + command.attemptId(), command.customerFee());
        }
      };
    }
  }
}
