package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.fluxpay.dto.WalletConvertRequest;
import com.fluxpay.dto.WalletConvertResponse;
import com.fluxpay.dto.WalletReceiveRequest;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.service.DemoFundingDisabledException;
import com.fluxpay.service.DemoFundingRetryException;
import com.fluxpay.service.DemoFundingService;
import com.fluxpay.service.FxSystemWalletNotFoundException;
import com.fluxpay.service.InsufficientWalletFundsException;
import com.fluxpay.service.WalletConversionService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WalletController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  M2ApiExceptionHandler.class
})
class WalletControllerTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String TOKEN = TestAuthHelper.mockJwt(USER_ID, "USER");

  @Autowired MockMvc mvc;
  @MockBean DemoFundingService funding;
  @MockBean WalletConversionService conversion;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void authenticateSignedTestToken() {
    CurrentUser user = TestAuthHelper.withUser(USER_ID, "priya@example.com", "USER");
    when(jwt.parse(TOKEN)).thenReturn(user);
  }

  @Test
  void signedRequestReturnsExistingEnvelopeAndDecimalStrings() throws Exception {
    WalletResponse response =
        new WalletResponse(
            "33333333-3333-3333-3333-333333333333",
            "USD",
            "500.0000",
            "0.0000",
            "500.0000",
            "M2-DEMO-44444444-4444-4444-4444-444444444444");
    when(funding.receiveDemo(
            eq(USER_ID), eq(new WalletReceiveRequest("USD", "500.0000")), eq("fund-1")))
        .thenReturn(response);

    mvc.perform(
            post("/api/wallets/receive-demo")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "fund-1")
                .header("X-Correlation-ID", "cid-wallet-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"USD\",\"amount\":\"500.0000\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-wallet-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-wallet-1"))
        .andExpect(jsonPath("$.data.walletId").value(response.walletId()))
        .andExpect(jsonPath("$.data.currency").value("USD"))
        .andExpect(jsonPath("$.data.balance").value("500.0000"))
        .andExpect(jsonPath("$.data.heldBalance").value("0.0000"))
        .andExpect(jsonPath("$.data.availableBalance").value("500.0000"))
        .andExpect(jsonPath("$.data.journalReference").value(response.journalReference()));
  }

  @Test
  void unauthenticatedRequestUsesExistingSecurityRejection() throws Exception {
    mvc.perform(
            post("/api/wallets/receive-demo")
                .header("Idempotency-Key", "fund-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"USD\",\"amount\":\"1.0000\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void missingIdempotencyKeyReturnsValidationError() throws Exception {
    when(funding.receiveDemo(eq(USER_ID), any(WalletReceiveRequest.class), isNull()))
        .thenThrow(new IllegalArgumentException("Idempotency-Key is required"));

    mvc.perform(
            post("/api/wallets/receive-demo")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Correlation-ID", "cid-wallet-400")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"USD\",\"amount\":\"1.0000\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.correlationId").value("cid-wallet-400"))
        .andExpect(jsonPath("$.code").value("VALIDATION"));
  }

  @Test
  void disabledFundingIsHiddenAsNotFound() throws Exception {
    when(funding.receiveDemo(eq(USER_ID), any(WalletReceiveRequest.class), eq("fund-disabled")))
        .thenThrow(new DemoFundingDisabledException());

    mvc.perform(
            post("/api/wallets/receive-demo")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "fund-disabled")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"USD\",\"amount\":\"1.0000\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void unresolvedRaceReturnsRetryConflict() throws Exception {
    when(funding.receiveDemo(eq(USER_ID), any(WalletReceiveRequest.class), eq("fund-race")))
        .thenThrow(new DemoFundingRetryException());

    mvc.perform(
            post("/api/wallets/receive-demo")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "fund-race")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currency\":\"USD\",\"amount\":\"1.0000\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RETRY"));
  }

  @Test
  void signedConversionReturnsCommittedDecimalStrings() throws Exception {
    WalletConvertResponse response =
        new WalletConvertResponse(
            "22222222-2222-2222-2222-222222222222",
            "33333333-3333-3333-3333-333333333333",
            "USD",
            "INR",
            "100.0000",
            "0.5000",
            "99.5000",
            "8308.2500",
            "83.50",
            "2026-09-11T01:02:03Z",
            false,
            true,
            "M2-FX-44444444-4444-4444-4444-444444444444");
    when(conversion.convert(
            eq(USER_ID), eq(new WalletConvertRequest("USD", "INR", "100.0000")), eq("fx-1")))
        .thenReturn(response);

    mvc.perform(
            post("/api/wallets/convert")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "fx-1")
                .header("X-Correlation-ID", "cid-convert-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"USD\",\"to\":\"INR\",\"amount\":\"100.0000\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value("cid-convert-1"))
        .andExpect(jsonPath("$.data.fee").value("0.5000"))
        .andExpect(jsonPath("$.data.creditedAmount").value("8308.2500"))
        .andExpect(jsonPath("$.data.rate").value("83.50"))
        .andExpect(jsonPath("$.data.mock").value(true));
  }

  @Test
  void insufficientConversionFundsReturnsUnprocessableEntity() throws Exception {
    when(conversion.convert(eq(USER_ID), any(WalletConvertRequest.class), eq("fx-low")))
        .thenThrow(new InsufficientWalletFundsException(USER_ID));

    mvc.perform(
            post("/api/wallets/convert")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "fx-low")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"USD\",\"to\":\"INR\",\"amount\":\"100.0000\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
  }

  @Test
  void missingFxSystemWalletReturnsServiceUnavailable() throws Exception {
    when(conversion.convert(eq(USER_ID), any(WalletConvertRequest.class), eq("fx-config")))
        .thenThrow(new FxSystemWalletNotFoundException("USD", "FX_CLEARING"));

    mvc.perform(
            post("/api/wallets/convert")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "fx-config")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"USD\",\"to\":\"INR\",\"amount\":\"1.0000\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("FX_UNAVAILABLE"));
  }
}
