package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.MethodSecurityConfig;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PaymentHoldResponse;
import com.fluxpay.service.PaymentConfirmationService;
import com.fluxpay.service.PaymentHoldService;
import com.fluxpay.service.PaymentService;
import com.fluxpay.service.QuoteService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, MethodSecurityConfig.class})
class PaymentHoldControllerMvcTest {
  private static final UUID PAYMENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Autowired private MockMvc mvc;
  @MockBean private PaymentService payments;
  @MockBean private QuoteService quotes;
  @MockBean private PaymentConfirmationService confirmations;
  @MockBean private PaymentHoldService hold;
  @MockBean private JwtUtil jwt;

  @BeforeEach
  void authenticate() {
    when(jwt.parse("user-token"))
        .thenReturn(new CurrentUser(USER_ID, "user@fluxpay.test", "CUSTOMER"));
  }

  @Test
  void holdExplainsWhyPayoutIsBlockedWithExpiry() throws Exception {
    when(hold.getHold(eq(USER_ID), eq(PAYMENT_ID)))
        .thenReturn(
            new PaymentHoldResponse(
                PAYMENT_ID,
                PaymentStatus.UNDER_REVIEW,
                true,
                false,
                Instant.parse("2026-09-17T10:00:00Z"),
                "MEDIUM",
                List.of("FIRST_TRANSFER_TO_RECIPIENT"),
                List.of(
                    "This is your first transfer to this recipient, so it needs an extra check."),
                "Your payout is on hold for compliance review.",
                null));

    mvc.perform(
            get("/api/payments/{id}/hold", PAYMENT_ID).header("Authorization", "Bearer user-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.onHold").value(true))
        .andExpect(jsonPath("$.data.canPayout").value(false))
        .andExpect(jsonPath("$.data.reasons[0]").value("FIRST_TRANSFER_TO_RECIPIENT"))
        .andExpect(
            jsonPath("$.data.reasonMessages[0]")
                .value(org.hamcrest.Matchers.containsString("first transfer")));
  }
}
