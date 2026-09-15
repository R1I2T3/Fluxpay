package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.eq;
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
import com.fluxpay.dto.LedgerEntryResponse;
import com.fluxpay.dto.LedgerPageResponse;
import com.fluxpay.dto.WalletSummaryResponse;
import com.fluxpay.service.DemoFundingService;
import com.fluxpay.service.WalletConversionService;
import com.fluxpay.service.WalletNotFoundException;
import com.fluxpay.service.WalletQueryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WalletController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  M2ApiExceptionHandler.class
})
class WalletReadControllerTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID WALLET_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String TOKEN = TestAuthHelper.mockJwt(USER_ID, "USER");

  @Autowired MockMvc mvc;
  @MockBean DemoFundingService funding;
  @MockBean WalletConversionService conversion;
  @MockBean WalletQueryService queries;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void authenticateSignedTestToken() {
    CurrentUser user = TestAuthHelper.withUser(USER_ID, "priya@example.com", "USER");
    when(jwt.parse(TOKEN)).thenReturn(user);
  }

  @Test
  void signedWalletListReturnsOnlyCustomerMoneyFields() throws Exception {
    when(queries.wallets(USER_ID))
        .thenReturn(
            List.of(new WalletSummaryResponse(WALLET_ID.toString(), "USD", "5.0000", "95.0000")));

    mvc.perform(
            get("/api/wallets")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Correlation-ID", "cid-wallet-list"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-wallet-list"))
        .andExpect(jsonPath("$.correlationId").value("cid-wallet-list"))
        .andExpect(jsonPath("$.data[0].walletId").value(WALLET_ID.toString()))
        .andExpect(jsonPath("$.data[0].currency").value("USD"))
        .andExpect(jsonPath("$.data[0].heldBalance").value("5.0000"))
        .andExpect(jsonPath("$.data[0].availableBalance").value("95.0000"))
        .andExpect(jsonPath("$.data[0].balance").doesNotExist())
        .andExpect(jsonPath("$.data[0].accountRole").doesNotExist());
  }

  @Test
  void walletListRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/wallets")).andExpect(status().isForbidden());
  }

  @Test
  void signedLedgerRequestReturnsBoundedPageWithoutInternalKey() throws Exception {
    LedgerEntryResponse entry =
        new LedgerEntryResponse(
            "33333333-3333-3333-3333-333333333333",
            "DEBIT",
            "10.5000",
            "USD",
            "M2-FX-journal",
            "FX conversion gross debit",
            "2026-09-11T04:05:06Z");
    when(queries.ledger(USER_ID, WALLET_ID, 1, 2))
        .thenReturn(new LedgerPageResponse(List.of(entry), 1, 2, 5, 3));

    mvc.perform(
            get("/api/wallets/{walletId}/ledger", WALLET_ID)
                .param("page", "1")
                .param("size", "2")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Correlation-ID", "cid-ledger"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value("cid-ledger"))
        .andExpect(jsonPath("$.data.page").value(1))
        .andExpect(jsonPath("$.data.size").value(2))
        .andExpect(jsonPath("$.data.totalElements").value(5))
        .andExpect(jsonPath("$.data.totalPages").value(3))
        .andExpect(jsonPath("$.data.entries[0].entryType").value("DEBIT"))
        .andExpect(jsonPath("$.data.entries[0].amount").value("10.5000"))
        .andExpect(jsonPath("$.data.entries[0].idempotencyKey").doesNotExist());
  }

  @Test
  void ledgerDefaultsToFirstTwentyEntries() throws Exception {
    when(queries.ledger(USER_ID, WALLET_ID, 0, 20))
        .thenReturn(new LedgerPageResponse(List.of(), 0, 20, 0, 0));

    mvc.perform(
            get("/api/wallets/{walletId}/ledger", WALLET_ID)
                .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(0))
        .andExpect(jsonPath("$.data.size").value(20));
  }

  @Test
  void foreignAndMissingWalletReturnNonLeakingNotFound() throws Exception {
    when(queries.ledger(eq(USER_ID), eq(WALLET_ID), eq(0), eq(20)))
        .thenThrow(new WalletNotFoundException());

    mvc.perform(
            get("/api/wallets/{walletId}/ledger", WALLET_ID)
                .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Wallet not found"));
  }

  @Test
  void unsafePageSizeReturnsValidationError() throws Exception {
    when(queries.ledger(eq(USER_ID), eq(WALLET_ID), eq(0), eq(101)))
        .thenThrow(new IllegalArgumentException("Page size must be between 1 and 100"));

    mvc.perform(
            get("/api/wallets/{walletId}/ledger", WALLET_ID)
                .param("size", "101")
                .header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION"));
  }

  @Test
  void ledgerRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/wallets/{walletId}/ledger", WALLET_ID)).andExpect(status().isForbidden());
  }
}
