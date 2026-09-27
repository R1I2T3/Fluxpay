package com.fluxpay.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.KycCase;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.common.contracts.WalletPort;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import com.fluxpay.service.*;
import com.fluxpay.web.advice.*;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class AdviceBoundaryTest {
  @BeforeEach
  void authenticate() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new CurrentUser(UUID.randomUUID(), "user@test.invalid", "USER"), null));
  }

  @AfterEach
  void clearAuthentication() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void negativePaymentPagePreservesBadRequestAndCorrelationId() throws Exception {
    var service =
        new PaymentService(
            mock(PaymentRepository.class),
            mock(RecipientRepository.class),
            mock(WalletPort.class),
            mock(KycGate.class),
            Clock.systemUTC(),
            new PaymentOperationService(
                mock(PaymentOperationRepository.class),
                new ObjectMapper(),
                Clock.systemUTC(),
                mock(org.springframework.transaction.PlatformTransactionManager.class)),
            new ObjectMapper());
    var mvc =
        mvc(
            new PaymentController(
                service,
                mock(QuoteService.class),
                mock(PaymentConfirmationService.class),
                mock(PaymentHoldService.class)));

    mvc.perform(
            get("/api/payments")
                .param("page", "-1")
                .header("X-Correlation-ID", "negative-payment-page"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.correlationId").value("negative-payment-page"));
  }

  @Test
  void kycPersistenceOptimisticLockPreservesConflictAndCorrelationId() throws Exception {
    var service = mock(KycUploadService.class);
    when(service.submit(any(), any(), any(), any()))
        .thenThrow(new ObjectOptimisticLockingFailureException(KycCase.class, UUID.randomUUID()));

    mvc(new KycDocumentController(service))
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(
                    "/api/kyc/applications")
                .file(
                    new org.springframework.mock.web.MockMultipartFile(
                        "files", "identity.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes()))
                .param("docType", "PAN")
                .param("docNumber", "TEST123")
                .header("X-Correlation-ID", "kyc-write-conflict"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"))
        .andExpect(jsonPath("$.correlationId").value("kyc-write-conflict"));
  }

  private MockMvc mvc(Object controller) {
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(
            new PaymentApiExceptionHandler(),
            new AuthKycApiExceptionHandler(),
            new PayoutApiExceptionHandler(),
            new GlobalExceptionHandler())
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .addFilter(new CorrelationIdFilter())
        .build();
  }

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
    mvc(new PaymentController(
            payments,
            mock(QuoteService.class),
            mock(PaymentConfirmationService.class),
            mock(PaymentHoldService.class)))
        .perform(
            get("/api/payments/11111111-1111-1111-1111-111111111111")
                .header("X-Correlation-ID", "payment-error"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"))
        .andExpect(jsonPath("$.correlationId").value("payment-error"));
  }

  @RestController
  static class UnrelatedController {
    @GetMapping("/unrelated")
    String fail() {
      throw new IllegalArgumentException("unrelated");
    }
  }
}
