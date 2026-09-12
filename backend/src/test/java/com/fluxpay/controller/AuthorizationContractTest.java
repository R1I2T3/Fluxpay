package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.config.M4ApiExceptionHandler;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import com.fluxpay.service.PaymentEligibilityGate;
import com.fluxpay.service.PaymentReader;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.PayoutExecutionService;
import com.fluxpay.service.RecoveryService;
import com.fluxpay.service.RouteAdminAuthorizer;
import com.fluxpay.service.RouteCatalogService;
import com.fluxpay.service.TimelineService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * PRD section 14 ownership/role proofs across the M4 surface. The frozen security chain has no 401
 * entry point, so anonymous requests are denied with 403; role and ownership denials map to {@code
 * FORBIDDEN} {@code ApiError}s.
 */
@WebMvcTest({
  RouteController.class,
  RouteAdminController.class,
  TimelineController.class,
  PayoutController.class
})
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  M4ApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
@ActiveProfiles("mock")
class AuthorizationContractTest {

  private static final UUID OWNER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:owner".getBytes(StandardCharsets.UTF_8));
  private static final UUID OTHER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:other".getBytes(StandardCharsets.UTF_8));
  private static final UUID R_STANDARD =
      UUID.nameUUIDFromBytes("fluxpay:route:STANDARD_BANK".getBytes(StandardCharsets.UTF_8));
  private static final String UPDATE_BODY =
      "{\"baseFee\":6.00,\"fxSpreadPercentage\":1.0,"
          + "\"estimatedMinutes\":120,\"successRate\":99.00,\"active\":true,\"version\":0}";

  @Autowired private MockMvc mvc;

  @MockBean private PaymentReader reader;
  @MockBean private RouteCatalogService catalog;
  @MockBean private RouteAdminAuthorizer authorizer;
  @MockBean private TimelineService timeline;
  @MockBean private PaymentEligibilityGate gate;
  @MockBean private PayoutExecutionService execution;
  @MockBean private RecoveryService recovery;
  @MockBean private PayoutAttemptRepository attempts;
  @MockBean private PayoutRouteRepository routes;
  @MockBean private JwtUtil jwt;

  private PaymentSnapshot payment;

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
  }

  @Test
  void adminEndpointRejectsCustomer() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(false);

    mvc.perform(
            put("/api/admin/routes/" + R_STANDARD.toString())
                .header("Authorization", MockSecurity.bearer(OTHER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-auth-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlationId").value("cid-auth-1"));
    verify(catalog, never()).updateRoute(anyString(), any());
  }

  @Test
  void adminEndpointRejectsAnonymous() throws Exception {
    mvc.perform(
            put("/api/admin/routes/" + R_STANDARD.toString())
                .header("X-Correlation-ID", "cid-auth-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(UPDATE_BODY))
        .andExpect(status().isForbidden());
    verify(catalog, never()).updateRoute(anyString(), any());
  }

  @Test
  void recommendRejectsNonOwner() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(false);

    mvc.perform(
            post("/api/payments/P-001/recommend-route")
                .header("Authorization", MockSecurity.bearer(OTHER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-auth-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\":\"CHEAPEST\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    verify(catalog, never()).recommend(anyString(), any(), anyString());
  }

  @Test
  void timelineRejectsNonOwner() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(false);

    mvc.perform(
            get("/api/payments/P-001/timeline")
                .header("Authorization", MockSecurity.bearer(OTHER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-auth-4"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    verify(timeline, never()).getTimeline(anyString());
  }

  @Test
  void submitRejectsNonOwner() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(false);

    mvc.perform(
            post("/api/payments/P-001/submit-payout")
                .header("Authorization", MockSecurity.bearer(OTHER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-auth-5")
                .header("Idempotency-Key", "key-auth-5")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"routeCode\":\"STANDARD_BANK\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    verify(gate, never()).assertActiveQuote(any(), anyString());
    verify(execution, never()).submit(anyString(), anyString(), anyString());
  }
}
