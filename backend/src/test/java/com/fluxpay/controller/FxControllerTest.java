package com.fluxpay.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.config.M2ApiExceptionHandler;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.service.FxQuoteService;
import com.fluxpay.service.FxUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FxController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  M2ApiExceptionHandler.class
})
class FxControllerTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String TOKEN = TestAuthHelper.mockJwt(USER_ID, "USER");

  @Autowired MockMvc mvc;
  @MockBean FxQuoteService quotes;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void authenticateSignedTestToken() {
    CurrentUser user = TestAuthHelper.withUser(USER_ID, "priya@example.com", "USER");
    when(jwt.parse(TOKEN)).thenReturn(user);
  }

  @Test
  void signedPreviewReturnsDecimalStringAndSnapshotMetadata() throws Exception {
    when(quotes.snapshot("USD", "INR"))
        .thenReturn(
            new FxSnapshot(
                "USD",
                "INR",
                new BigDecimal("83.50"),
                Instant.parse("2026-09-11T01:02:03Z"),
                true,
                false));

    mvc.perform(
            get("/api/fx/rate")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Correlation-ID", "cid-fx-1")
                .queryParam("from", "USD")
                .queryParam("to", "INR"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-fx-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-fx-1"))
        .andExpect(jsonPath("$.data.from").value("USD"))
        .andExpect(jsonPath("$.data.to").value("INR"))
        .andExpect(jsonPath("$.data.rate").value("83.50"))
        .andExpect(jsonPath("$.data.fetchedAt").value("2026-09-11T01:02:03Z"))
        .andExpect(jsonPath("$.data.stale").value(true))
        .andExpect(jsonPath("$.data.mock").value(false));
  }

  @Test
  void unauthenticatedPreviewIsRejected() throws Exception {
    mvc.perform(get("/api/fx/rate").queryParam("from", "USD").queryParam("to", "INR"))
        .andExpect(status().isForbidden());
  }

  @Test
  void invalidPairReturnsValidationEnvelope() throws Exception {
    when(quotes.snapshot("USD", "USD"))
        .thenThrow(new IllegalArgumentException("FX currencies must be different"));

    mvc.perform(
            get("/api/fx/rate")
                .header("Authorization", "Bearer " + TOKEN)
                .queryParam("from", "USD")
                .queryParam("to", "USD"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION"));
  }

  @Test
  void unavailableRateReturnsServiceUnavailable() throws Exception {
    when(quotes.snapshot("EUR", "INR")).thenThrow(new FxUnavailableException());

    mvc.perform(
            get("/api/fx/rate")
                .header("Authorization", "Bearer " + TOKEN)
                .queryParam("from", "EUR")
                .queryParam("to", "INR"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("FX_UNAVAILABLE"));
  }
}
