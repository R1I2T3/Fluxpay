package com.fluxpay.config;

import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.service.PaymentReader;
import com.fluxpay.service.PaymentSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("mock")
public class InMemoryPaymentReader implements PaymentReader {

  public static final UUID P001_SENDER_WALLET =
      UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes(StandardCharsets.UTF_8));
  public static final UUID P001_CLEARING_WALLET =
      UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes(StandardCharsets.UTF_8));
  public static final UUID P001_SENDER_USER =
      UUID.nameUUIDFromBytes("fluxpay:P-001:user".getBytes(StandardCharsets.UTF_8));

  private final Map<String, PaymentSnapshot> store =
      Map.of(
          "P-001",
          new PaymentSnapshot(
              "P-001",
              P001_SENDER_USER,
              P001_SENDER_WALLET,
              P001_CLEARING_WALLET,
              new BigDecimal("1000.00"),
              "USD",
              "KES",
              PaymentStatus.ROUTED));

  @Override
  public PaymentSnapshot get(String paymentId) {
    PaymentSnapshot payment = store.get(paymentId);
    if (payment == null) {
      throw new NoSuchElementException("payment " + paymentId + " not found");
    }
    return payment;
  }
}
