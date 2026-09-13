package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;

import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.beans.WalletOperation;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class OperationStateTest {
  @Test
  void constructorRejectsPartialPaymentCompletionFields() {
    assertThatThrownBy(() -> payment(null, "{}", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> payment(200, null, "{}")).isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 199, 600})
  void invalidFinalHttpStatusCannotCreateOrCompletePaymentOperation(int status) {
    assertThatThrownBy(() -> payment(status, "{}", "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    var pending = payment(null, null, "{}");
    assertThatThrownBy(() -> pending.complete(status, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(pending.status()).isEqualTo("IN_PROGRESS");
    assertThat(pending.outcomeStatus()).isNull();
    assertThat(pending.responseData()).isNull();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "event-id", "null", "[]", "42", "{} trailing"})
  void invalidResponseCannotAlterPendingState(String response) {
    var payment = payment(null, null, "{}");
    var wallet = new WalletOperation(UUID.randomUUID(), "CONVERT", "key", "{}", "journal");
    assertThatThrownBy(() -> payment.complete(200, response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> wallet.complete(response))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(payment.status()).isEqualTo("IN_PROGRESS");
    assertThat(payment.outcomeStatus()).isNull();
    assertThat(payment.responseData()).isNull();
    assertThat(wallet.getStatus()).isEqualTo("IN_PROGRESS");
    assertThat(wallet.getResponseSnapshot()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"bad", "[]", "null", "{} trailing"})
  void invalidRequestCannotCreateOperation(String request) {
    assertThatThrownBy(() -> payment(null, null, request))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new WalletOperation(UUID.randomUUID(), "CONVERT", "key", request, "journal"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void completedSnapshotsCannotBeOverwritten() {
    var payment = payment(null, null, "{}");
    var wallet = new WalletOperation(UUID.randomUUID(), "CONVERT", "key", "{}", "journal");
    payment.complete(201, "{\"result\":\"first\"}");
    wallet.complete("{\"result\":\"first\"}");
    assertThatThrownBy(() -> payment.complete(200, "{\"result\":\"second\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> wallet.complete("{\"result\":\"second\"}"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(payment.outcomeStatus()).isEqualTo(201);
    assertThat(payment.responseData()).isEqualTo("{\"result\":\"first\"}");
    assertThat(wallet.getResponseSnapshot()).isEqualTo("{\"result\":\"first\"}");
  }

  PaymentOperation payment(Integer status, String response, String request) {
    return new PaymentOperation(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "SUBMIT",
        "key",
        request,
        status,
        response,
        UUID.randomUUID(),
        Instant.EPOCH);
  }
}
