package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.PaymentEvent;
import com.fluxpay.beans.PayoutAttempt;
import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawUuidJpaMappingTest {

  @Test
  void rawUuidColumnsDeclareTheOracleRaw16Type() throws NoSuchFieldException {
    List<Field> rawUuidFields =
        List.of(
            PaymentEvent.class.getDeclaredField("eventId"),
            PaymentEvent.class.getDeclaredField("paymentId"),
            PayoutAttempt.class.getDeclaredField("id"),
            PayoutAttempt.class.getDeclaredField("paymentId"),
            PayoutAttempt.class.getDeclaredField("routeId"));

    assertThat(rawUuidFields)
        .allSatisfy(
            field ->
                assertThat(field.getAnnotation(Column.class).columnDefinition())
                    .isEqualTo("RAW(16)"));
  }
}
