package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fluxpay.common.contracts.*;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.controller.PayoutController;
import com.fluxpay.dto.PayoutApi;
import com.fluxpay.dto.PayoutOutcome;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class PayoutOperationIntegrationTest {
  final String paymentId = PaymentOperationServiceTest.PAYMENT.toString();
  final PayoutExecutionService execution = mock(PayoutExecutionService.class);
  final PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
  final PaymentEligibilityGate gate = mock(PaymentEligibilityGate.class);

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void replayReturnsStoredResponseEvenWhenAttemptAndQuoteHaveChanged() {
    try (var db = new OperationDatabase()) {
      var controller = controller(db);
      when(execution.submit(anyString(), anyString(), any()))
          .thenReturn(PayoutOutcome.completed().withEventId("event-1"));
      var first =
          controller.submit(paymentId, new PayoutApi.SubmitRequest("BANK"), "submit", request());
      when(execution.submit(anyString(), anyString(), any()))
          .thenThrow(new AssertionError("Replay must bypass execution"));
      doThrow(new AssertionError("Replay must bypass quote lookup"))
          .when(gate)
          .assertActiveQuote(any(), any());
      when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(any()))
          .thenThrow(new AssertionError("Replay must bypass attempt rebuilding"));
      var replay =
          controller.submit(paymentId, new PayoutApi.SubmitRequest("BANK"), "submit", request());
      assertThat(replay.data()).isEqualTo(first.data());
      assertThat(replay.data().status()).isEqualTo("COMPLETED");
      assertThat(replay.data().routeCode()).isEqualTo("BANK");
      assertThat(db.payments.findAll())
          .singleElement()
          .satisfies(
              op -> {
                assertThat(op.status()).isEqualTo("COMPLETED");
                assertThat(op.responseData())
                    .contains("\"routeCode\":\"BANK\"", "\"status\":\"COMPLETED\"");
              });
      assertThatThrownBy(
              () ->
                  controller.submit(
                      paymentId, new PayoutApi.SubmitRequest("OTHER"), "submit", request()))
          .isInstanceOfSatisfying(
              BusinessException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
      assertThatThrownBy(() -> controller.retry(paymentId, "submit", request()))
          .isInstanceOfSatisfying(
              BusinessException.class, e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
  }

  @Test
  void uncertainProviderFailureKeepsDurableReservationAndBlocksDuplicate() {
    try (var db = new OperationDatabase()) {
      var controller = controller(db);
      when(execution.submit(anyString(), anyString(), any()))
          .thenThrow(new IllegalStateException("Provider delivery is unknown"));
      assertThatThrownBy(
              () ->
                  controller.submit(
                      paymentId, new PayoutApi.SubmitRequest("BANK"), "uncertain", request()))
          .isInstanceOf(IllegalStateException.class);
      doThrow(new AssertionError("Uncertain request must not be delivered again"))
          .when(execution)
          .submit(anyString(), anyString(), any());
      assertThatThrownBy(
              () ->
                  controller.submit(
                      paymentId, new PayoutApi.SubmitRequest("BANK"), "uncertain", request()))
          .isInstanceOfSatisfying(
              BusinessException.class,
              e -> assertThat(e.code()).isEqualTo("OPERATION_IN_PROGRESS"));
      assertThat(db.payments.findAll())
          .singleElement()
          .satisfies(
              op -> {
                assertThat(op.status()).isEqualTo("IN_PROGRESS");
                assertThat(op.responseData()).isNull();
                assertThat(op.outcomeStatus()).isNull();
              });
    }
  }

  PayoutController controller(OperationDatabase db) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new CurrentUser(PaymentOperationServiceTest.USER, "user@test.invalid", "USER"),
                null));
    var reader = mock(PaymentReader.class);
    when(reader.get(paymentId))
        .thenReturn(PaymentOperationServiceTest.snapshot(PaymentOperationServiceTest.PAYMENT));
    var authorizer = mock(RouteAdminAuthorizer.class);
    when(authorizer.isOwner(any(), any())).thenReturn(true);
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(any())).thenReturn(Optional.empty());
    return new PayoutController(
        reader,
        authorizer,
        gate,
        execution,
        mock(RecoveryService.class),
        attempts,
        mock(PayoutRouteRepository.class),
        PaymentOperationServiceTest.service(db));
  }

  MockHttpServletRequest request() {
    var request = new MockHttpServletRequest();
    request.setAttribute("correlationId", "payout-operation");
    return request;
  }
}
