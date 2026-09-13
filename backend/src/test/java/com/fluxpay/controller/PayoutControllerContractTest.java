package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fluxpay.common.contracts.*;
import com.fluxpay.common.security.*;
import com.fluxpay.common.web.*;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PayoutApi;
import com.fluxpay.exception.QuoteExpiredException;
import com.fluxpay.service.*;
import com.fluxpay.web.advice.PayoutApiExceptionHandler;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PayoutController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  PayoutApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
class PayoutControllerContractTest {
  static final UUID OWNER = UUID.randomUUID(), PAYMENT = UUID.randomUUID();
  @Autowired MockMvc mvc;
  @MockBean PaymentOperationService operations;
  @MockBean PaymentReader reader;
  @MockBean RouteAdminAuthorizer authorizer;
  @MockBean PayoutExecutionService execution;
  @MockBean RecoveryService recovery;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void setup() {
    MockSecurity.stubJwt(jwt);
    when(reader.get(PAYMENT.toString()))
        .thenReturn(
            new PaymentSnapshot(
                PAYMENT.toString(),
                OWNER,
                UUID.randomUUID(),
                null,
                new BigDecimal("100"),
                "USD",
                "INR",
                PaymentStatus.PROCESSING));
    when(authorizer.isOwner(any(), any())).thenReturn(true);
  }

  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
      String action, String body) {
    return post("/api/payments/" + PAYMENT + "/" + action)
        .header("Authorization", MockSecurity.bearer(OWNER, "CUSTOMER"))
        .header("X-Correlation-ID", "cid")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  @ParameterizedTest
  @ValueSource(strings = {"submit-payout", "retry-payout", "switch-route", "refund"})
  void everyMutationRequiresCallerKey(String action) throws Exception {
    mvc.perform(request(action, "{\"routeCode\":\"BANK\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.correlationId").value("cid"));
  }

  @Test
  void ownerSubmitReturnsStoredOutcomeAndDurableEventIdentity() throws Exception {
    var result =
        new PayoutApi.OutcomeResponse(
            1, "BANK", "COMPLETED", "ref", null, List.of(), false, "durable-event");
    when(execution.perform(OWNER, "submit", "SUBMIT", PAYMENT, "BANK", null, "cid"))
        .thenReturn(result);
    mvc.perform(
            request("submit-payout", "{\"routeCode\":\"BANK\"}")
                .header("Idempotency-Key", "submit"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid"))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.attemptNumber").value(1))
        .andExpect(jsonPath("$.data.originalEventId").value("durable-event"));
  }

  @Test
  void nonOwnerCannotSubmit() throws Exception {
    when(authorizer.isOwner(any(), any())).thenReturn(false);
    mvc.perform(
            request("submit-payout", "{\"routeCode\":\"BANK\"}")
                .header("Idempotency-Key", "submit"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    verifyNoInteractions(execution);
  }

  @Test
  void expiredQuoteRetainsHttpContract() throws Exception {
    when(execution.perform(any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new QuoteExpiredException("expired"));
    mvc.perform(
            request("submit-payout", "{\"routeCode\":\"BANK\"}")
                .header("Idempotency-Key", "submit"))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
  }

  @Test
  void replacementQuoteIdentityIsIncludedInSwitchAction() throws Exception {
    var quote = UUID.randomUUID();
    when(execution.perform(OWNER, "switch", "SWITCH", PAYMENT, "BANK2", quote, "cid"))
        .thenReturn(
            new PayoutApi.OutcomeResponse(
                2, "BANK2", "COMPLETED", "ref2", null, List.of(), false, "event2"));
    mvc.perform(
            request("switch-route", "{\"routeCode\":\"BANK2\",\"quoteId\":\"" + quote + "\"}")
                .header("Idempotency-Key", "switch"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.routeCode").value("BANK2"));
  }

  @Test
  void emptyRouteIsBadRequest() throws Exception {
    mvc.perform(request("switch-route", "{\"routeCode\":\"\"}").header("Idempotency-Key", "switch"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(execution);
  }
}
