package com.fluxpay.dto;

import com.fluxpay.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminStatisticsPaymentPageResponse(
    AdminStatisticsResponse.Metadata meta,
    PaymentStatus status,
    int page,
    int size,
    long totalElements,
    long totalPages,
    List<Row> items) {
  public AdminStatisticsPaymentPageResponse {
    items = List.copyOf(items);
  }

  public record Row(
      UUID paymentId,
      Instant createdAt,
      String sourceAmount,
      String sourceCurrency,
      PaymentStatus status) {}
}
