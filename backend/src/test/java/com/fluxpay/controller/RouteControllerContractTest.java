package com.fluxpay.controller;

import static org.hamcrest.Matchers.closeTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.PaymentReader;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.RankedRouteQuote;
import com.fluxpay.dto.RouteQuote;
import com.fluxpay.dto.RouteRecommendation;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.RouteCatalogService;
import com.fluxpay.service.RouteReliabilityService;
import com.fluxpay.web.advice.PayoutApiExceptionHandler;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract for the customer route catalog: list plus owner-gated recommendation. */
@WebMvcTest(RouteController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  PayoutApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
class RouteControllerContractTest {

  private static final UUID OWNER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:owner".getBytes(StandardCharsets.UTF_8));
  private static final UUID OTHER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:other".getBytes(StandardCharsets.UTF_8));
  private static final UUID R_STANDARD =
      UUID.nameUUIDFromBytes("fluxpay:route:STANDARD_BANK".getBytes(StandardCharsets.UTF_8));
  private static final UUID R_INSTANT =
      UUID.nameUUIDFromBytes("fluxpay:route:INSTANT_PAYOUT".getBytes(StandardCharsets.UTF_8));

  @Autowired private MockMvc mvc;

  @MockBean private PaymentReader reader;
  @MockBean private RouteCatalogService catalog;
  @MockBean private RouteAdminAuthorizer authorizer;
  @MockBean private JwtUtil jwt;

  private PaymentSnapshot payment;
  private TransferRoute standard;
  private TransferRoute instant;

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
            PaymentStatus.PROCESSING);
    standard =
        TransferRoute.seed(
            R_STANDARD,
            "STANDARD_BANK",
            "Standard Bank Rail",
            "Standard Bank",
            "STANDARD",
            "5.00",
            "0.8",
            240,
            "99.50");
    instant =
        TransferRoute.seed(
            R_INSTANT,
            "INSTANT_PAYOUT",
            "Instant Payout",
            "Instant Payout Co",
            "INSTANT",
            "8.50",
            "2.0",
            5,
            "98.00");
  }

  @Test
  void listRoutesReturnsCatalogEntriesWithMetrics() throws Exception {
    when(catalog.listRoutes()).thenReturn(List.of(instant, standard));
    when(catalog.metricFor(R_STANDARD))
        .thenReturn(
            new RouteReliabilityService.RouteReliability(
                R_STANDARD, new BigDecimal("99.50"), 3L, 1L));
    when(catalog.metricFor(R_INSTANT))
        .thenReturn(
            new RouteReliabilityService.RouteReliability(
                R_INSTANT, new BigDecimal("98.00"), 1L, 1L));

    mvc.perform(
            get("/api/routes")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-routes-1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-routes-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-routes-1"))
        .andExpect(jsonPath("$.data.routes[0].routeCode").value("INSTANT_PAYOUT"))
        .andExpect(jsonPath("$.data.routes[1].routeCode").value("STANDARD_BANK"))
        .andExpect(jsonPath("$.data.routes[1].successCount").value(3))
        .andExpect(jsonPath("$.data.routes[1].totalAttempts").value(4));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"NO_ACTIVE_ROUTES", "INVALID_AMOUNT"})
  void quoteFailuresPreserveStatusCodeAndCorrelation(String code) {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    when(catalog.recommend(eq("P-001"), eq(RoutePreference.BALANCED), anyString()))
        .thenThrow(
            new com.fluxpay.exception.BusinessException(
                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                code,
                "Quote unavailable"));
    org.assertj.core.api.Assertions.assertThatCode(
            () ->
                mvc.perform(
                        post("/api/payments/P-001/recommend-route")
                            .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                            .header("X-Correlation-ID", "quote-error"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.correlationId").value("quote-error")))
        .doesNotThrowAnyException();
  }

  @Test
  void recommendReturnsQuotesWithMarketRate() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    RouteQuote standardQuote =
        new RouteQuote(
            standard,
            new BigDecimal("148.0000"),
            new BigDecimal("146.8160"),
            new BigDecimal("146081.9200"),
            new BigDecimal("5.0000"),
            new BigDecimal("995.0000"),
            new BigDecimal("99.50"));
    RouteQuote instantQuote =
        new RouteQuote(
            instant,
            new BigDecimal("148.0000"),
            new BigDecimal("145.0400"),
            new BigDecimal("143444.5600"),
            new BigDecimal("11.0000"),
            new BigDecimal("989.0000"),
            new BigDecimal("98.00"));
    when(catalog.recommend(eq("P-001"), eq(RoutePreference.BALANCED), anyString()))
        // Task 7 owns this path — compile-restoration only
        .thenReturn(
            new RouteRecommendation(
                new RankedRouteQuote(standardQuote, standardQuote.recipientAmount(), 1),
                List.of(
                    new RankedRouteQuote(standardQuote, standardQuote.recipientAmount(), 1),
                    new RankedRouteQuote(instantQuote, instantQuote.recipientAmount(), 2)),
                "test recommendation"));

    mvc.perform(
            post("/api/payments/P-001/recommend-route")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-reco-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\":\"BALANCED\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-reco-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-reco-1"))
        .andExpect(jsonPath("$.data.paymentId").value("P-001"))
        .andExpect(jsonPath("$.data.recommendedRouteId").value(standard.getId().toString()))
        .andExpect(jsonPath("$.data.quotes.length()").value(2))
        .andExpect(jsonPath("$.data.quotes[0].marketRate", closeTo(148.0, 0.0001)))
        .andExpect(jsonPath("$.data.quotes[1].marketRate", closeTo(148.0, 0.0001)));
  }

  @Test
  void recommendRejectsNonOwner() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(false);

    mvc.perform(
            post("/api/payments/P-001/recommend-route")
                .header("Authorization", MockSecurity.bearer(OTHER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-reco-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"preference\":\"BALANCED\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlationId").value("cid-reco-2"));
    verify(catalog, never()).recommend(anyString(), any(), anyString());
  }
}
