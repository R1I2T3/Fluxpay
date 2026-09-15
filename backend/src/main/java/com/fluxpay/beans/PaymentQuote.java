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

  @Column(name = "route", nullable = false, length = 50)
  private String route;

  @Column(name = "market_rate", precision = 19, scale = 6)
  private BigDecimal marketRate;

  @Column(name = "spread_percent", precision = 9, scale = 6)
  private BigDecimal spreadPercent;

  @Column(name = "offered_rate", precision = 19, scale = 6)
  private BigDecimal offeredRate;

  @Column(name = "fee_amount", precision = 19, scale = 4)
  private BigDecimal feeAmount;

  @Column(name = "recipient_amount", precision = 19, scale = 4)
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
      String route,
      BigDecimal marketRate,
      BigDecimal spread,
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
    spreadPercent = spread;
    offeredRate = offered;
    feeAmount = fee;
    recipientAmount = recipient;
    estimatedMinutes = eta;
    this.recommended = recommended;
    policyVersion = "source-fee-v1";
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

  public String route() {
    return route;
  }

  public BigDecimal marketRate() {
    return marketRate;
  }

  public BigDecimal spreadPercent() {
    return spreadPercent;
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
}
