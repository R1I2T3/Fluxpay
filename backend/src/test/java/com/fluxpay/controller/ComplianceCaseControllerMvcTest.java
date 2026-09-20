package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.MethodSecurityConfig;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.ComplianceCaseService;
import com.fluxpay.web.advice.ComplianceCaseApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ComplianceCaseController.class)
@Import({SecurityConfig.class, MethodSecurityConfig.class, ComplianceCaseApiExceptionHandler.class})
class ComplianceCaseControllerMvcTest {
  private static final UUID CASE_ID = UUID.fromString("4fa2bff6-3339-4081-8b7f-ac669448a835");

  @Autowired private MockMvc mvc;
  @MockBean private ComplianceCaseService cases;
  @MockBean private JwtUtil jwt;

  @BeforeEach
  void authenticateAdmin() {
    when(jwt.parse("admin-token"))
        .thenReturn(new CurrentUser(UUID.randomUUID(), "admin@fluxpay.test", "ADMIN"));
  }

  @Test
  void expiredQuoteDuringApprovalReturnsItsDomainErrorInsteadOfAuthenticationFailure()
      throws Exception {
    when(cases.approve(eq(CASE_ID), any(), eq("admin@fluxpay.test")))
        .thenThrow(
            new BusinessException(
                HttpStatus.GONE, "QUOTE_EXPIRED", "The selected quote has expired during review."));

    mvc.perform(
            put("/api/compliance/cases/{id}/approve", CASE_ID)
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decisionReason\":\"Reviewed policy evidence.\"}"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("QUOTE_EXPIRED"));
  }
}
