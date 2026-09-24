package com.fluxpay.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.MethodSecurityConfig;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.domain.PaymentStatus;
import com.fluxpay.dto.PaymentOperationsResponse;
import com.fluxpay.service.PaymentOperationsService;
import com.fluxpay.web.advice.PayoutApiExceptionHandler;
import java.time.Instant;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

@WebMvcTest(PaymentOperationsAdminController.class)
@Import({
  SecurityConfig.class,
  MethodSecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  PayoutApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
class PaymentOperationsAdminControllerMvcTest {
  private static final UUID PAYMENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID CUSTOMER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

  @Autowired private MockMvc mvc;
  @MockBean private PaymentOperationsService operations;
  @MockBean private JwtUtil jwt;

  @BeforeEach
  void setUp() {
    MockSecurity.stubJwt(jwt);
  }

  @Test
  void adminCanReadPaymentOperations() throws Exception {
    when(operations.get(PAYMENT_ID.toString())).thenReturn(response());

    mvc.perform(
            get("/api/admin/payments/{paymentId}/operations", PAYMENT_ID)
                .header("Authorization", MockSecurity.bearer(CUSTOMER_ID, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.payment.id").value(PAYMENT_ID.toString()));
  }

  @Test
  void customerCannotReadPaymentOperations() throws Exception {
    mvc.perform(
            get("/api/admin/payments/{paymentId}/operations", PAYMENT_ID)
                .header("Authorization", MockSecurity.bearer(CUSTOMER_ID, "CUSTOMER")))
        .andExpect(status().isForbidden());

    verify(operations, never()).get(anyString());
  }

  @Test
  void anonymousCannotReadPaymentOperations() throws Exception {
    mvc.perform(get("/api/admin/payments/{paymentId}/operations", PAYMENT_ID))
        .andExpect(status().isUnauthorized());

    verify(operations, never()).get(anyString());
  }

  @Test
  void malformedPaymentIdReturnsNotFound() throws Exception {
    when(operations.get("not-a-uuid")).thenThrow(new NoSuchElementException("payment not found"));

    mvc.perform(
            get("/api/admin/payments/not-a-uuid/operations")
                .header("Authorization", MockSecurity.bearer(CUSTOMER_ID, "ADMIN")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void controllerHasNoWriteMappings() {
    assertThat(
            Arrays.stream(PaymentOperationsAdminController.class.getDeclaredMethods())
                .filter(
                    method ->
                        method.isAnnotationPresent(PostMapping.class)
                            || method.isAnnotationPresent(PutMapping.class)
                            || method.isAnnotationPresent(PatchMapping.class)
                            || method.isAnnotationPresent(DeleteMapping.class))
                .toList())
        .isEmpty();
  }

  private PaymentOperationsResponse response() {
    return new PaymentOperationsResponse(
        new PaymentOperationsResponse.Payment(
            PAYMENT_ID, PaymentStatus.PROCESSING, null, 0, Instant.EPOCH, Instant.EPOCH),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        java.util.List.of(),
        new PaymentOperationsResponse.Recovery(
            0, PaymentOperationsResponse.RecoveryDecision.NOT_REQUIRED, null));
  }
}
