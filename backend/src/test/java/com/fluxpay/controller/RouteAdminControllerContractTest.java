package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.config.M4ApiExceptionHandler;
import com.fluxpay.service.RouteAdminAuthorizer;
import com.fluxpay.service.RouteCatalogService;
import com.fluxpay.service.RouteMetrics;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RouteAdminController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  M4ApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
@ActiveProfiles("mock")
class RouteAdminControllerContractTest {

  private static final UUID ADMIN_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:admin".getBytes(StandardCharsets.UTF_8));
  private static final UUID CUSTOMER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:customer".getBytes(StandardCharsets.UTF_8));
  private static final UUID R_STANDARD =
      UUID.nameUUIDFromBytes("fluxpay:route:STANDARD_BANK".getBytes(StandardCharsets.UTF_8));

  @Autowired private MockMvc mvc;

  @MockBean private RouteCatalogService catalog;
  @MockBean private RouteAdminAuthorizer authorizer;
  @MockBean private JwtUtil jwt;

  private PayoutRoute standard;

  @BeforeEach
  void setUp() {
    MockSecurity.stubJwt(jwt);
    standard =
        PayoutRoute.seed(
            R_STANDARD,
            "STANDARD_BANK",
            "Standard Bank Rail",
            "Standard Bank",
            "STANDARD",
            "5.00",
            "0.8",
            240,
            "99.50");
  }

  @Test
  void adminUpdateReturnsUpdatedRoute() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(catalog.updateRoute(anyString(), any())).thenReturn(standard);
    when(catalog.metricFor(R_STANDARD))
        .thenReturn(new RouteMetrics.RouteMetric(R_STANDARD, 3L, 4L));

    mvc.perform(
            put("/api/admin/routes/" + standard.getId().toString())
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .header("X-Correlation-ID", "cid-admin-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"baseFee\":6.00,\"fxSpreadPercentage\":1.0,"
                        + "\"estimatedMinutes\":120,\"successRate\":99.00,"
                        + "\"active\":true,\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-admin-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-admin-1"))
        .andExpect(jsonPath("$.data.routeCode").value("STANDARD_BANK"))
        .andExpect(jsonPath("$.data.successCount").value(3));
  }

  @Test
  void customerUpdateIsForbidden() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(false);

    mvc.perform(
            put("/api/admin/routes/" + standard.getId().toString())
                .header("Authorization", MockSecurity.bearer(CUSTOMER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-admin-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"baseFee\":6.00,\"fxSpreadPercentage\":1.0,"
                        + "\"estimatedMinutes\":120,\"successRate\":99.00,"
                        + "\"active\":true,\"version\":0}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlationId").value("cid-admin-2"));
    verify(catalog, never()).updateRoute(anyString(), any());
  }

  @Test
  void staleVersionIsConflict() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(catalog.updateRoute(anyString(), any()))
        .thenThrow(
            new ObjectOptimisticLockingFailureException(
                PayoutRoute.class, standard.getId().toString()));

    mvc.perform(
            put("/api/admin/routes/" + standard.getId().toString())
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .header("X-Correlation-ID", "cid-admin-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"baseFee\":6.00,\"fxSpreadPercentage\":1.0,"
                        + "\"estimatedMinutes\":120,\"successRate\":99.00,"
                        + "\"active\":true,\"version\":5}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"))
        .andExpect(jsonPath("$.correlationId").value("cid-admin-3"));
  }

  @Test
  void missingTokenIsRejectedByFrozenChain() throws Exception {
    // The frozen SecurityConfig has no 401 entry point, so anonymous requests are denied with
    // 403. Assert the actual frozen behavior instead of inventing a 401 contract.
    mvc.perform(
            put("/api/admin/routes/" + standard.getId().toString())
                .header("X-Correlation-ID", "cid-admin-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"baseFee\":6.00,\"fxSpreadPercentage\":1.0,"
                        + "\"estimatedMinutes\":120,\"successRate\":99.00,"
                        + "\"active\":true,\"version\":0}"))
        .andExpect(status().isForbidden());
  }
}
