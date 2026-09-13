package com.fluxpay.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.*;
import com.fluxpay.web.advice.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class AdviceBoundaryTest {
  @Test
  void payoutAdviceDoesNotTranslateUnrelatedControllerErrors() {
    var mvc =
        MockMvcBuilders.standaloneSetup(new UnrelatedController())
            .setControllerAdvice(new PayoutApiExceptionHandler())
            .build();
    assertThatThrownBy(() -> mvc.perform(get("/unrelated")))
        .hasRootCauseInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void paymentErrorPreservesStatusCodeAndCorrelationId() throws Exception {
    PaymentService payments = mock(PaymentService.class);
    when(payments.detail(any(), any()))
        .thenThrow(
            new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", "Payment not found."));
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new PaymentController(
                    payments, mock(QuoteService.class), mock(PaymentConfirmationService.class)))
            .setControllerAdvice(new PaymentApiExceptionHandler())
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .addFilter(new CorrelationIdFilter())
            .build();
    try {
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  new CurrentUser(UUID.randomUUID(), "user@test.invalid", "USER"), null));
      mvc.perform(
              get("/api/payments/11111111-1111-1111-1111-111111111111")
                  .header("X-Correlation-ID", "payment-error"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
          .andExpect(jsonPath("$.correlationId").value("payment-error"));
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  @RestController
  static class UnrelatedController {
    @GetMapping("/unrelated")
    String fail() {
      throw new IllegalArgumentException("unrelated");
    }
  }
}
