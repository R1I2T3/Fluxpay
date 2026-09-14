package com.fluxpay.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.service.FxQuoteService;
import com.fluxpay.web.advice.WalletFxApiExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Development identity impersonation via {@code X-Local-User-Id} was removed in every mode: every
 * request needs a valid JWT, regardless of the active Spring profile. Concrete subclasses rerun the
 * same proofs under the {@code default}, {@code local} and {@code development} profiles.
 */
abstract class LocalAuthBypassRegressionTest {
  private static final UUID OWNER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String TOKEN = TestAuthHelper.mockJwt(OWNER_ID, "CUSTOMER");

  @Autowired MockMvc mvc;
  @MockBean FxQuoteService quotes;
  @MockBean JwtUtil jwt;

  @Test
  void localUserIdHeaderAloneIsRejected() throws Exception {
    mvc.perform(
            get("/api/fx/rate")
                .header("X-Local-User-Id", OWNER_ID.toString())
                .queryParam("from", "USD")
                .queryParam("to", "INR"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    org.mockito.Mockito.verifyNoInteractions(quotes);
  }

  @Test
  void malformedJwtWithLocalUserIdHeaderIsRejected() throws Exception {
    when(jwt.parse("not-a-jwt")).thenThrow(new IllegalArgumentException("bad token"));

    mvc.perform(
            get("/api/fx/rate")
                .header("Authorization", "Bearer not-a-jwt")
                .header("X-Local-User-Id", OWNER_ID.toString())
                .queryParam("from", "USD")
                .queryParam("to", "INR"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    org.mockito.Mockito.verifyNoInteractions(quotes);
  }

  @Test
  void impersonationOfAnotherKnownUserWithoutJwtIsRejected() throws Exception {
    mvc.perform(
            get("/api/fx/rate")
                .header("X-Local-User-Id", OTHER_ID.toString())
                .queryParam("from", "USD")
                .queryParam("to", "INR"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    org.mockito.Mockito.verifyNoInteractions(quotes);
  }

  @Test
  void validJwtIsStillAccepted() throws Exception {
    CurrentUser user = TestAuthHelper.withUser(OWNER_ID, "owner@example.com", "CUSTOMER");
    when(jwt.parse(TOKEN)).thenReturn(user);
    when(quotes.snapshot("USD", "INR"))
        .thenReturn(
            new FxSnapshot(
                "USD",
                "INR",
                new BigDecimal("83.50"),
                Instant.parse("2026-09-11T01:02:03Z"),
                false));

    mvc.perform(
            get("/api/fx/rate")
                .header("Authorization", "Bearer " + TOKEN)
                .queryParam("from", "USD")
                .queryParam("to", "INR"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.rate").value("83.50"));
  }
}

@WebMvcTest(FxController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  WalletFxApiExceptionHandler.class
})
@ActiveProfiles("default")
class DefaultLocalAuthBypassRegressionTest extends LocalAuthBypassRegressionTest {}

@WebMvcTest(FxController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  WalletFxApiExceptionHandler.class
})
@ActiveProfiles("local")
class LocalProfileAuthBypassRegressionTest extends LocalAuthBypassRegressionTest {}

@WebMvcTest(FxController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  WalletFxApiExceptionHandler.class
})
@ActiveProfiles("development")
class DevelopmentProfileAuthBypassRegressionTest extends LocalAuthBypassRegressionTest {}
