package com.fluxpay.dto;

import java.util.Set;

public final class EventTopics {
  private EventTopics() {}

  public static final String PAYMENT_INITIATED = "payment.initiated";
  public static final String PAYMENT_ROUTE_SELECTED = "payment.route.selected";
  public static final String PAYMENT_SCREENING_COMPLETED = "payment.screening.completed";
  public static final String PAYOUT_SUBMITTED = "payout.submitted";
  public static final String PAYOUT_FAILED = "payout.failed";
  public static final String PAYOUT_COMPLETED = "payout.completed";
  public static final String PAYMENT_REFUNDED = "payment.refunded";

  public static final Set<String> ALL =
      Set.of(
          PAYMENT_INITIATED,
          PAYMENT_ROUTE_SELECTED,
          PAYMENT_SCREENING_COMPLETED,
          PAYOUT_SUBMITTED,
          PAYOUT_FAILED,
          PAYOUT_COMPLETED,
          PAYMENT_REFUNDED);
}
