package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fluxpay.common.contracts.*;
import com.fluxpay.common.security.*;
import com.fluxpay.common.web.*;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.RecoveryResult;
import com.fluxpay.service.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PayoutController.class)
@Import({
  SecurityConfig.class,
  MethodSecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  GlobalExceptionHandler.class
})
class RefundGateTest {
  final UUID sender = UUID.randomUUID(), admin = UUID.randomUUID(), payment = UUID.randomUUID();
  @Autowired MockMvc mvc;
  @MockBean PaymentReader reader;
  @MockBean RouteAdminAuthorizer authorizer;
  @MockBean PayoutExecutionService execution;
  @MockBean RecoveryService recovery;
  @MockBean PayoutReconciler reconciler;
  @MockBean PaymentOperationService operations;
  @MockBean JwtUtil jwt;

  @BeforeEach
  void setup() {
    MockSecurity.stubJwt(jwt);
    when(reader.get(payment.toString()))
        .thenReturn(
            new PaymentSnapshot(
                payment.toString(),
                sender,
                UUID.randomUUID(),
                null,
                new BigDecimal("100"),
                "USD",
                "INR",
                PaymentStatus.FAILED));
    when(authorizer.isOwner(any(), any())).thenReturn(true);
    when(recovery.refundFunded(sender, payment, "cid"))
        .thenReturn(new RecoveryResult(payment.toString(), "refund-event", false));
    when(operations.execute(any(), any(), any(), any(), any(), eq(RecoveryResult.class), any()))
        .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(6)).get());
  }

  @Test
  void customerRefundIsForbidden() throws Exception {
    mvc.perform(
            post("/api/payments/" + payment + "/refund")
                .header("Authorization", MockSecurity.bearer(sender, "CUSTOMER"))
                .header("Idempotency-Key", "key"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(recovery);
  }

  @Test
  void adminRefundBelongsToAdminButCreditsSender() throws Exception {
    mvc.perform(
            post("/api/admin/payments/" + payment + "/refund")
                .header("Authorization", MockSecurity.bearer(admin, "ADMIN"))
                .header("Idempotency-Key", "key")
                .header("X-Correlation-ID", "cid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.eventId").value("refund-event"));
    verify(operations)
        .execute(
            eq(admin),
            eq("key"),
            eq("REFUND"),
            eq(payment),
            any(),
            eq(RecoveryResult.class),
            any());
    verify(recovery).refundFunded(sender, payment, "cid");
  }

  @Test
  void customerCannotUseAdminRefund() throws Exception {
    mvc.perform(
            post("/api/admin/payments/" + payment + "/refund")
                .header("Authorization", MockSecurity.bearer(sender, "CUSTOMER"))
                .header("Idempotency-Key", "key"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(recovery);
  }

  @Test
  void onlyAdminCanReconcile() throws Exception {
    when(reconciler.reconcile(payment, "cid"))
        .thenReturn(
            new com.fluxpay.dto.PayoutApi.OutcomeResponse(
                1, "BANK", "COMPLETED", "ref", null, java.util.List.of(), false, "event"));
    mvc.perform(
            post("/api/admin/payments/" + payment + "/reconcile")
                .header("Authorization", MockSecurity.bearer(sender, "CUSTOMER")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/admin/payments/" + payment + "/reconcile")
                .header("Authorization", MockSecurity.bearer(admin, "ADMIN"))
                .header("X-Correlation-ID", "cid"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
  }
}
