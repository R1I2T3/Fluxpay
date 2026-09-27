package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.MethodSecurityConfig;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.dto.HoldPreviewResponse;
import com.fluxpay.service.PaymentHoldPreviewService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentHoldPreviewController.class)
@Import({SecurityConfig.class, MethodSecurityConfig.class})
class PaymentHoldPreviewControllerMvcTest {
  private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
  private static final UUID RECIPIENT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

  @Autowired private MockMvc mvc;
  @MockBean private PaymentHoldPreviewService preview;
  @MockBean private JwtUtil jwt;

  @BeforeEach
  void authenticate() {
    when(jwt.parse("user-token"))
        .thenReturn(new CurrentUser(USER_ID, "user@fluxpay.test", "CUSTOMER"));
  }

  @Test
  void previewTellsReviewScreenWhetherHoldIsLikely() throws Exception {
    when(preview.preview(eq(USER_ID), any()))
        .thenReturn(
            new HoldPreviewResponse(
                true,
                "MEDIUM",
                List.of("FIRST_TRANSFER_TO_RECIPIENT"),
                List.of(
                    "This is your first transfer to this recipient, so it needs an extra check.")));

    mvc.perform(
            post("/api/payments/hold-preview")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"recipientId\":\""
                        + RECIPIENT_ID
                        + "\",\"sourceAmount\":\"10.00\",\"sourceCurrency\":\"USD\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.likely").value(true))
        .andExpect(jsonPath("$.data.reasons[0]").value("FIRST_TRANSFER_TO_RECIPIENT"));
  }
}
