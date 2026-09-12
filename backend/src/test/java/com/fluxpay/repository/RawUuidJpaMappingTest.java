package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.beans.PayoutAttempt;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RawUuidJpaMappingTest {

  @Test
  void rawUuidColumnsDeclareTheOracleRaw16Type() throws NoSuchFieldException {
    List<Field> rawUuidFields =
        List.of(
            PaymentEvent.class.getDeclaredField("eventId"),
            PayoutAttempt.class.getDeclaredField("id"),
            PayoutAttempt.class.getDeclaredField("routeId"));

    assertThat(rawUuidFields)
        .allSatisfy(
            field -> {
              assertThat(field.getType()).isEqualTo(UUID.class);
              assertThat(field.getAnnotation(Column.class).columnDefinition()).isEqualTo("RAW(16)");
            });
  }

  @Test
  void paymentIdColumnsDeclareVarchar50Type() throws NoSuchFieldException {
    List<Field> paymentIdFields =
        List.of(
            PaymentEvent.class.getDeclaredField("paymentId"),
            PayoutAttempt.class.getDeclaredField("paymentId"));

    assertThat(paymentIdFields)
        .allSatisfy(
            field -> {
              assertThat(field.getType()).isEqualTo(String.class);
              assertThat(field.getAnnotation(Column.class).columnDefinition())
                  .isEqualTo("VARCHAR2(50)");
            });
  }
}
