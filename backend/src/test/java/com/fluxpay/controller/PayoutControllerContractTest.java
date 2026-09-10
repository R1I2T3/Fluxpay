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
import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.config.M4ApiExceptionHandler;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import com.fluxpay.service.PaymentEligibilityGate;
import com.fluxpay.service.PaymentReader;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.PayoutExecutionService;
import com.fluxpay.service.QuoteExpiredException;
import com.fluxpay.service.RecoveryService;
import com.fluxpay.service.RouteAdminAuthorizer;
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
  M4ApiExceptionHandler.class,
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

  @BeforeEach
  void setUp() {
    MockSecurity.stubJwt(jwt);
    payment =
        new PaymentSnapshot(
            "P-001",
            OWNER_ID,
            UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes(StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes(StandardCharsets.UTF_8)),
            new BigDecimal("1000.00"),
            "USD",
            "KES",
            PaymentStatus.ROUTED);
    completedAttempt =
        PayoutAttempt.initiated(
            ATTEMPT_ID, "P-001", 1, ROUTE_ID, Instant.parse("2026-09-04T10:00:00Z"));
    completedAttempt.markProcessing();
    completedAttempt.markCompleted("SB-1");
  }

  @Test
  void ownerSubmitWithValidQuoteReturnsAttempt() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    when(gate.confirmIdempotent(eq(payment), eq("key-1")))
        .thenReturn(new PaymentEligibilityGate.ConfirmOutcome(false, "evt-1"));
    when(execution.submit("P-001", "STANDARD_BANK", "cid-pay-1"))
        .thenReturn(PayoutOutcome.completed());
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(completedAttempt));

    mvc.perform(
            post("/api/payments/P-001/submit-payout")
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
    verify(execution).submit("P-001", "STANDARD_BANK", "cid-pay-1");
  }

  @Test
  void nonOwnerSubmitIsForbidden() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(false);

    mvc.perform(
            post("/api/payments/P-001/submit-payout")
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
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    doThrow(new QuoteExpiredException("quote for P-001 is expired or missing"))
        .when(gate)
        .assertActiveQuote(eq(payment), eq("STANDARD_BANK"));

    mvc.perform(
            post("/api/payments/P-001/submit-payout")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-pay-3")
                .header("Idempotency-Key", "key-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"STANDARD_BANK\"}"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"))
        .andExpect(jsonPath("$.correlationId").value("cid-pay-3"));
    verify(gate, never()).confirmIdempotent(any(), anyString());
    verify(execution, never()).submit(anyString(), anyString(), anyString());
  }

  @Test
  void duplicateIdempotencyKeyReplaysWithoutNewAttempt() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    when(gate.confirmIdempotent(eq(payment), eq("key-dup")))
        .thenReturn(new PaymentEligibilityGate.ConfirmOutcome(true, "orig-evt-9"));
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc("P-001"))
        .thenReturn(Optional.of(completedAttempt));

    mvc.perform(
            post("/api/payments/P-001/submit-payout")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-pay-4")
                .header("Idempotency-Key", "key-dup")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"STANDARD_BANK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.alreadyConfirmed").value(true))
        .andExpect(jsonPath("$.data.originalEventId").value("orig-evt-9"))
        .andExpect(jsonPath("$.data.attemptNumber").value(1));
    verify(execution, never()).submit(anyString(), anyString(), anyString());
  }

  @Test
  void switchRouteWithoutBodyIsBadRequest() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);

    mvc.perform(
            post("/api/payments/P-001/switch-route")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-pay-5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }
}
