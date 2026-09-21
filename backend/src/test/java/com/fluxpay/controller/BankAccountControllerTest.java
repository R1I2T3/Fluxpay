package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.*;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.dto.*;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.BankAccountService;
import com.fluxpay.web.advice.WalletFxApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BankAccountController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  WalletFxApiExceptionHandler.class
})
class BankAccountControllerTest {
  private static final UUID USER = UUID.randomUUID();
  private static final UUID BANK = UUID.randomUUID();
  private static final String TOKEN = TestAuthHelper.mockJwt(USER, "USER");
  @Autowired MockMvc mvc;
  @MockBean BankAccountService banks;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void authenticate() {
    when(jwt.parse(TOKEN)).thenReturn(TestAuthHelper.withUser(USER, "test@example.com", "USER"));
  }

  @Test
  void listUsesAuthenticatedOwnerAndReturnsOnlyRedactedMetadata() throws Exception {
    when(banks.list(USER)).thenReturn(java.util.List.of(
        new BankAccountResponse(BANK.toString(), "Bank", "1234", "USD", "VERIFIED")));
    mvc.perform(get("/api/bank-accounts").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].id").value(BANK.toString()))
        .andExpect(jsonPath("$.data[0].accountLast4").value("1234"))
        .andExpect(jsonPath("$.data[0].accountNumber").doesNotExist())
        .andExpect(jsonPath("$.data[0].userId").doesNotExist());
    verify(banks).list(USER);
  }

  @Test
  void listRequiresAuthentication() throws Exception {
    mvc.perform(get("/api/bank-accounts")).andExpect(status().isUnauthorized());
    verifyNoInteractions(banks);
  }

  @Test
  void linkReturnsRedactedEnvelope() throws Exception {
    when(banks.link(eq(USER), eq(new BankLinkRequest("Bank", "1234", "USD")), eq("link")))
        .thenReturn(new BankAccountResponse(BANK.toString(), "Bank", "1234", "USD", "VERIFIED"));
    mvc.perform(
            post("/api/bank-accounts/link")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "link")
                .header("X-Correlation-ID", "bank-cid")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bankName\":\"Bank\",\"accountLast4\":\"1234\",\"currency\":\"USD\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.correlationId").value("bank-cid"))
        .andExpect(jsonPath("$.data.accountLast4").value("1234"))
        .andExpect(jsonPath("$.data.accountNumber").doesNotExist());
  }

  @Test
  void topupCapUses409AndStableCode() throws Exception {
    when(banks.topup(eq(USER), eq(BANK), eq(new BankTopupRequest("1", null)), eq("cap")))
        .thenThrow(
            new BusinessException(HttpStatus.CONFLICT, "TOPUP_CAP_EXCEEDED", "Daily cap exceeded"));
    mvc.perform(
            post("/api/bank-accounts/" + BANK + "/topup")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "cap")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"1\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TOPUP_CAP_EXCEEDED"));
  }

  @Test
  void fullAccountNumberIsRejectedEvenAlongsideValidLast4() throws Exception {
    mvc.perform(
            post("/api/bank-accounts/link")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", "bad")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"bankName\":\"Bank\",\"accountLast4\":\"1234\",\"currency\":\"USD\",\"accountNumber\":\"123456781234\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(banks);
  }
}
