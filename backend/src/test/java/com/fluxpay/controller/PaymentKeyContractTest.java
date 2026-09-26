package com.fluxpay.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.service.*;
import com.fluxpay.web.advice.PaymentApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PaymentKeyContractTest {
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(strings = {"cancel", "quotes"})
  void mutatingPaymentActionsRequireCallerKey(String action) throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new CurrentUser(UUID.randomUUID(), "user@test.invalid", "USER"), null));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new PaymentController(
                    mock(PaymentService.class),
                    mock(QuoteService.class),
                    mock(PaymentConfirmationService.class),
                    mock(PaymentHoldService.class)))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new PaymentApiExceptionHandler(), new GlobalExceptionHandler())
            .addFilters(new CorrelationIdFilter())
            .build();
    mvc.perform(
            post("/api/payments/22222222-2222-2222-2222-222222222222/" + action)
                .header("X-Correlation-ID", "key-validation"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
        .andExpect(jsonPath("$.correlationId").value("key-validation"));
  }
}
