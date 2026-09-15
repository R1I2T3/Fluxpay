package com.fluxpay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.FluxPayApplication;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.beans.User;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.FxSnapshotSource;
import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.messaging.EventTopics;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentEventRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import com.fluxpay.repository.UserRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.time.Instant;
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
class BackendAcceptanceIT {
  private static final UUID SYSTEM_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private WebApplicationContext webContext;
  @Autowired private ObjectMapper json;
  @Autowired private UserRepository users;
  @Autowired private WalletRepository wallets;
  @Autowired private PayoutRouteRepository routes;
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
    provisionRoutes();
    adminToken = provisionAdmin();
  }

  @Test
  void fullPaymentRecoveryAndMessagingLifecycleUsesDedicatedInfrastructure() throws Exception {
    assertThat(flyway.info().pending()).isEmpty();
    assertThat(flyway.info().applied()).hasSize(5);

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

    JsonNode kyc =
        request(
            post("/api/kyc/applications"),
            customerToken,
            null,
            Map.of(
                "docType",
                "PASSPORT",
                "docNumber",
                "ACCEPTANCE-" + UUID.randomUUID(),
                "documents",
                java.util.List.of(
                    Map.of(
                        "fileName", "acceptance.pdf",
                        "fileType", "application/pdf",
                        "fileSize", 1024))),
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

    PaymentFlow successful = createAndConfirm(customerToken, sourceWallet, recipientId, "100.0000");
    String successKey = "acceptance-payout-success-" + UUID.randomUUID();
    JsonNode payout =
        request(
            post("/api/payments/" + successful.paymentId + "/submit-payout"),
            customerToken,
            successKey,
            Map.of("routeCode", "STANDARD_BANK"),
            200);
    assertThat(payout.path("data").path("status").asText()).isEqualTo("COMPLETED");
    String completedEventId = payout.path("data").path("originalEventId").asText();
    JsonNode payoutReplay =
        request(
            post("/api/payments/" + successful.paymentId + "/submit-payout"),
            customerToken,
            successKey,
            Map.of("routeCode", "STANDARD_BANK"),
            200);
    assertThat(payoutReplay.path("data").path("originalEventId").asText())
        .isEqualTo(completedEventId);
    assertJournalEntryCount(successful.paymentId, 3);

    PaymentFlow retried = createAndConfirm(customerToken, sourceWallet, recipientId, "210.0000");
    JsonNode failedRetryable =
        submit(customerToken, retried.paymentId, "STANDARD_BANK", "retryable-first");
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
            submit(customerToken, switched.paymentId, "STANDARD_BANK", "switch-first")
                .path("data")
                .path("status")
                .asText())
        .isEqualTo("FAILED");
    JsonNode switchedResult =
        request(
            post("/api/payments/" + switched.paymentId + "/switch-route"),
            customerToken,
            "acceptance-switch-" + UUID.randomUUID(),
            Map.of("routeCode", "INSTANT_PAYOUT", "quoteId", switched.instantQuoteId),
            200);
    assertThat(switchedResult.path("data").path("status").asText()).isEqualTo("COMPLETED");
    assertThat(switchedResult.path("data").path("selectedQuote").path("routeCode").asText())
        .isEqualTo("INSTANT_PAYOUT");

    PaymentFlow refunded = createAndConfirm(customerToken, sourceWallet, recipientId, "230.0000");
    assertThat(
            submit(customerToken, refunded.paymentId, "STANDARD_BANK", "refund-first")
                .path("data")
                .path("status")
                .asText())
        .isEqualTo("FAILED");
    String refundKey = "acceptance-refund-" + UUID.randomUUID();
    JsonNode refund =
        request(
            post("/api/payments/" + refunded.paymentId + "/refund"),
            customerToken,
            refundKey,
            Map.of(),
            200);
    JsonNode refundReplay =
        request(
            post("/api/payments/" + refunded.paymentId + "/refund"),
            customerToken,
            refundKey,
            Map.of(),
            200);
    assertThat(refundReplay.path("data").path("eventId").asText())
        .isEqualTo(refund.path("data").path("eventId").asText());
    assertExactRefund(refunded.paymentId, "230.0000", "225.0000", "5.0000");

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
    JsonNode standard = find(quote.path("data").path("quotes"), "route", "STANDARD_BANK");
    JsonNode instant = find(quote.path("data").path("quotes"), "route", "INSTANT_PAYOUT");
    JsonNode confirmed =
        request(
            post("/api/payments/" + paymentId + "/confirm"),
            token,
            "acceptance-confirm-" + UUID.randomUUID(),
            Map.of("quoteId", standard.path("id").asText()),
            200);
    assertThat(confirmed.path("data").path("status").asText()).isEqualTo("PROCESSING");
    return new PaymentFlow(paymentId, instant.path("id").asText());
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

  private void provisionRoutes() {
    route("STANDARD_BANK", "STANDARD", 240);
    route("INSTANT_PAYOUT", "INSTANT", 5);
    route("LOCAL_PARTNER", "LOCAL_PARTNER", 60);
  }

  private void route(String code, String type, int minutes) {
    PayoutRoute route =
        routes
            .findByCode(code)
            .orElseGet(
                () ->
                    PayoutRoute.seed(
                        UUID.nameUUIDFromBytes(("acceptance:" + code).getBytes()),
                        code,
                        code,
                        "Acceptance provider",
                        type,
                        "5.0000",
                        "0.500000",
                        minutes,
                        "99.00"));
    route.update("5.0000", "0.500000", minutes, "99.00", true);
    routes.saveAndFlush(route);
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

  private record PaymentFlow(String paymentId, String instantQuoteId) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class TestIntegrations {
    @Bean
    @Primary
    FxSnapshotSource acceptanceFxSource() {
      return (from, to) ->
          new FxSnapshot(from, to, new BigDecimal("83.500000"), Instant.now(), false);
    }

    @Bean
    PayoutProvider acceptanceStandardProvider() {
      return new PayoutProvider() {
        private final Map<String, AtomicInteger> attempts = new ConcurrentHashMap<>();

        @Override
        public String code() {
          return "STANDARD_BANK";
        }

        @Override
        public PayoutResult submit(PayoutCmd command) {
          int attempt =
              attempts
                  .computeIfAbsent(command.paymentId(), ignored -> new AtomicInteger())
                  .incrementAndGet();
          BigDecimal amount = command.amount();
          boolean failsOnce = amount.compareTo(new BigDecimal("210.0000")) == 0 && attempt == 1;
          boolean alwaysFails = amount.compareTo(new BigDecimal("220.0000")) >= 0;
          if (failsOnce || alwaysFails) {
            return PayoutResult.failed(
                "TEST_PROVIDER_REJECTED",
                "Acceptance provider rejected payout",
                command.customerFee());
          }
          return PayoutResult.ok("ACCEPTANCE-" + command.attemptId(), command.customerFee());
        }
      };
    }

    @Bean
    PayoutProvider acceptanceInstantProvider() {
      return provider("INSTANT_PAYOUT");
    }

    @Bean
    PayoutProvider acceptanceLocalPartnerProvider() {
      return provider("LOCAL_PARTNER");
    }

    private static PayoutProvider provider(String code) {
      return new PayoutProvider() {
        @Override
        public String code() {
          return code;
        }

        @Override
        public PayoutResult submit(PayoutCmd command) {
          return PayoutResult.ok(code + "-" + command.attemptId(), command.customerFee());
        }
      };
    }
  }
}
