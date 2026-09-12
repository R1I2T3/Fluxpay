package com.fluxpay.beans;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_quotes")
public class PaymentQuote {
  @Id private UUID id;

  @Column(name = "payment_id")
  private UUID paymentId;

  private int generation;

  @Enumerated(EnumType.STRING)
  private QuoteRoute route;

  @Column(name = "market_rate")
  private BigDecimal marketRate;

  @Column(name = "spread_bps")
  private int spreadBps;

  @Column(name = "offered_rate")
  private BigDecimal offeredRate;

  @Column(name = "fee_amount")
  private BigDecimal feeAmount;

  @Column(name = "recipient_amount")
  private BigDecimal recipientAmount;

  @Column(name = "estimated_minutes")
  private int estimatedMinutes;

  private boolean recommended;

  @Column(name = "policy_version")
  private String policyVersion;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  protected PaymentQuote() {}

  public PaymentQuote(
      UUID id,
      UUID paymentId,
      int generation,
      QuoteRoute route,
      BigDecimal marketRate,
      int spread,
      BigDecimal offered,
      BigDecimal fee,
      BigDecimal recipient,
      int eta,
      boolean recommended,
      Instant now,
      Instant expires) {
    this.id = id;
    this.paymentId = paymentId;
    this.generation = generation;
    this.route = route;
    this.marketRate = marketRate;
    spreadBps = spread;
    offeredRate = offered;
    feeAmount = fee;
    recipientAmount = recipient;
    estimatedMinutes = eta;
    this.recommended = recommended;
    policyVersion = "m3-demo-v1";
    createdAt = now;
    expiresAt = expires;
  }

  public UUID id() {
    return id;
  }

  public UUID paymentId() {
    return paymentId;
  }

  public int generation() {
    return generation;
  }

  public QuoteRoute route() {
    return route;
  }

  public BigDecimal marketRate() {
    return marketRate;
  }

  public int spreadBps() {
    return spreadBps;
  }

  public BigDecimal offeredRate() {
    return offeredRate;
  }

  public BigDecimal feeAmount() {
    return feeAmount;
  }

  public BigDecimal recipientAmount() {
    return recipientAmount;
  }

  public int estimatedMinutes() {
    return estimatedMinutes;
  }

  public boolean recommended() {
    return recommended;
  }

  public Instant expiresAt() {
    return expiresAt;
  }

  public PaymentQuote withRecommendation(boolean value) {
    return new PaymentQuote(
        id,
        paymentId,
        generation,
        route,
        marketRate,
        spreadBps,
        offeredRate,
        feeAmount,
        recipientAmount,
        estimatedMinutes,
        value,
        createdAt,
        expiresAt);
  }
}
