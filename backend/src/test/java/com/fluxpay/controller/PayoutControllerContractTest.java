package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.common.contracts.PaymentEligibilityGate;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.exception.QuoteExpiredException;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.PayoutExecutionService;
import com.fluxpay.service.RecoveryService;
import com.fluxpay.web.advice.PayoutApiExceptionHandler;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract for payout submission: owner gate, quote gate, and idempotent confirm. */
@WebMvcTest(PayoutController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  PayoutApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
class PayoutControllerContractTest {

  private static final UUID OWNER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:owner".getBytes(StandardCharsets.UTF_8));
  private static final UUID OTHER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:other".getBytes(StandardCharsets.UTF_8));
  private static final UUID ATTEMPT_ID =
      UUID.nameUUIDFromBytes("fluxpay:attempt:a-1".getBytes(StandardCharsets.UTF_8));
  private static final UUID ROUTE_ID =
      UUID.nameUUIDFromBytes("fluxpay:route:STANDARD_BANK".getBytes(StandardCharsets.UTF_8));

  @Autowired private MockMvc mvc;

  @MockBean private com.fluxpay.service.PaymentOperationService operations;
  @MockBean private PaymentReader reader;
  @MockBean private RouteAdminAuthorizer authorizer;
  @MockBean private PaymentEligibilityGate gate;
  @MockBean private PayoutExecutionService execution;
  @MockBean private RecoveryService recovery;
  @MockBean private PayoutAttemptRepository attempts;
  @MockBean private PayoutRouteRepository routes;
  @MockBean private JwtUtil jwt;

  private PaymentSnapshot payment;
  private PayoutAttempt completedAttempt;

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"submit-payout", "retry-payout", "switch-route", "refund"})
  void everyPayoutMutationRequiresCallerKey(String action) throws Exception {
    when(reader.get("22222222-2222-2222-2222-222222222222")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    mvc.perform(
            post("/api/payments/22222222-2222-2222-2222-222222222222/" + action)
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "required-payout-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"STANDARD_BANK\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.correlationId").value("required-payout-key"));
  }

  @BeforeEach
  void setUp() {
    MockSecurity.stubJwt(jwt);
    payment =
        new PaymentSnapshot(
            "22222222-2222-2222-2222-222222222222",
            OWNER_ID,
            UUID.nameUUIDFromBytes(
                "fluxpay:22222222-2222-2222-2222-222222222222:sender"
                    .getBytes(StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes(
                "fluxpay:22222222-2222-2222-2222-222222222222:clearing"
                    .getBytes(StandardCharsets.UTF_8)),
            new BigDecimal("1000.00"),
            "USD",
            "KES",
            PaymentStatus.ROUTED);
    completedAttempt =
        PayoutAttempt.initiated(
            ATTEMPT_ID,
            "22222222-2222-2222-2222-222222222222",
            1,
            ROUTE_ID,
            Instant.parse("2026-09-04T10:00:00Z"));
    completedAttempt.markProcessing();
    completedAttempt.markCompleted("SB-1");
  }

  @Test
  void ownerSubmitWithValidQuoteReturnsAttempt() throws Exception {
    when(reader.get("22222222-2222-2222-2222-222222222222")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    when(operations.reserve(any(), eq("key-1"), eq("SUBMIT"), any(), any(), any()))
        .thenReturn(
            new com.fluxpay.service.PaymentOperationService.Reservation<>(ATTEMPT_ID, null, null));
    when(execution.submit("22222222-2222-2222-2222-222222222222", "STANDARD_BANK", "cid-pay-1"))
        .thenReturn(PayoutOutcome.completed().withEventId("evt-published"));
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(
            "22222222-2222-2222-2222-222222222222"))
        .thenReturn(Optional.of(completedAttempt));

    mvc.perform(
            post("/api/payments/22222222-2222-2222-2222-222222222222/submit-payout")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-pay-1")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"STANDARD_BANK\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-pay-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-pay-1"))
        .andExpect(jsonPath("$.data.routeCode").value("STANDARD_BANK"))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.attemptNumber").value(1))
        .andExpect(jsonPath("$.data.alreadyConfirmed").value(false))
        .andExpect(jsonPath("$.data.originalEventId").doesNotExist());
    verify(execution).submit("22222222-2222-2222-2222-222222222222", "STANDARD_BANK", "cid-pay-1");
  }

  @Test
  void nonOwnerSubmitIsForbidden() throws Exception {
    when(reader.get("22222222-2222-2222-2222-222222222222")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(false);

    mvc.perform(
            post("/api/payments/22222222-2222-2222-2222-222222222222/submit-payout")
                .header("Authorization", MockSecurity.bearer(OTHER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-pay-2")
                .header("Idempotency-Key", "key-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"STANDARD_BANK\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlationId").value("cid-pay-2"));
    verify(gate, never()).assertActiveQuote(any(), anyString());
    verify(execution, never()).submit(anyString(), anyString(), anyString());
  }

  @Test
  void expiredQuoteDoesNotCreateAttempt() throws Exception {
    when(operations.reserve(any(), eq("key-3"), eq("SUBMIT"), any(), any(), any()))
        .thenReturn(
            new com.fluxpay.service.PaymentOperationService.Reservation<>(ATTEMPT_ID, null, null));
    when(reader.get("22222222-2222-2222-2222-222222222222")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    doThrow(
            new QuoteExpiredException(
                "quote for 22222222-2222-2222-2222-222222222222 is expired or missing"))
        .when(gate)
        .assertActiveQuote(eq(payment), eq("STANDARD_BANK"));

    mvc.perform(
            post("/api/payments/22222222-2222-2222-2222-222222222222/submit-payout")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-pay-3")
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"STANDARD_BANK\"}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"))
        .andExpect(jsonPath("$.correlationId").value("cid-pay-3"));
    verify(execution, never()).submit(anyString(), anyString(), anyString());
  }

  @Test
  void duplicateIdempotencyKeyReplaysWithoutNewAttempt() throws Exception {
    doThrow(new QuoteExpiredException("expired since original execution"))
        .when(gate)
        .assertActiveQuote(any(), anyString());
    when(reader.get("22222222-2222-2222-2222-222222222222")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    when(operations.reserve(any(), eq("key-dup"), eq("SUBMIT"), any(), any(), any()))
        .thenReturn(
            new com.fluxpay.service.PaymentOperationService.Reservation<>(
                ATTEMPT_ID,
                new com.fluxpay.dto.PayoutApi.OutcomeResponse(
                    1,
                    "STANDARD_BANK",
                    "COMPLETED",
                    "SB-1",
                    null,
                    java.util.List.of(),
                    false,
                    null),
                200));
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(
            "22222222-2222-2222-2222-222222222222"))
        .thenReturn(Optional.of(completedAttempt));

    mvc.perform(
            post("/api/payments/22222222-2222-2222-2222-222222222222/submit-payout")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-pay-4")
                .header("Idempotency-Key", "key-dup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"STANDARD_BANK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.alreadyConfirmed").value(false))
        .andExpect(jsonPath("$.data.originalEventId").doesNotExist())
        .andExpect(jsonPath("$.data.attemptNumber").value(1));
    verify(gate, never()).assertActiveQuote(any(), anyString());
    verify(execution, never()).submit(anyString(), anyString(), anyString());
  }

  @Test
  void switchRouteWithoutBodyIsBadRequest() throws Exception {
    when(reader.get("22222222-2222-2222-2222-222222222222")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);

    mvc.perform(
            post("/api/payments/22222222-2222-2222-2222-222222222222/switch-route")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-pay-5")
                .header("Idempotency-Key", "invalid-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }
}
