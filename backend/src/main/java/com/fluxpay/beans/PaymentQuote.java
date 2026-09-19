package com.fluxpay.beans;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_quotes")
public class PaymentQuote {
  @Id private UUID id;

  @Column(name = "payment_id")
  private UUID paymentId;

  private int generation;

  @Column(name = "route_id", nullable = false)
  private UUID routeId;

  @Column(name = "route_code", nullable = false, length = 50)
  private String route;

  @Column(name = "provider_id", nullable = false)
  private UUID providerId;

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

  @Column(name = "effective_reliability", precision = 9, scale = 6)
  private BigDecimal effectiveReliability;

  @Column(name = "ranking_score", precision = 19, scale = 12)
  private BigDecimal rankingScore;

  @Column(name = "ranking_position")
  private int rankingPosition;

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
      UUID routeId,
      String routeCode,
      UUID providerId,
      BigDecimal marketRate,
      BigDecimal spread,
      BigDecimal offered,
      BigDecimal fee,
      BigDecimal recipient,
      int eta,
      BigDecimal effectiveReliability,
      BigDecimal rankingScore,
      int rankingPosition,
      boolean recommended,
      Instant now,
      Instant expires) {
    this.id = id;
    this.paymentId = paymentId;
    this.generation = generation;
    this.routeId = routeId;
    route = routeCode;
    this.providerId = providerId;
    this.marketRate = marketRate;
    spreadPercent = spread;
    offeredRate = offered;
    feeAmount = fee;
    recipientAmount = recipient;
    estimatedMinutes = eta;
    this.effectiveReliability = effectiveReliability;
    this.rankingScore = rankingScore;
    this.rankingPosition = rankingPosition;
    this.recommended = recommended;
    policyVersion = "source-fee-v1";
    createdAt = now;
    expiresAt = expires;
  }

  /**
   * Legacy unit-test constructor without route identity snapshots. Derives deterministic identity
   * values from the route code so mocked persistence keeps working; production quote generation
   * uses the full snapshot constructor.
   */
  @Deprecated
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
    this(
        id,
        paymentId,
        generation,
        UUID.nameUUIDFromBytes(("route:" + route).getBytes(StandardCharsets.UTF_8)),
        route,
        UUID.nameUUIDFromBytes(("provider:" + route).getBytes(StandardCharsets.UTF_8)),
        marketRate,
        spread,
        offered,
        fee,
        recipient,
        eta,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        1,
        recommended,
        now,
        expires);
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

  public UUID routeId() {
    return routeId;
  }

  public String route() {
    return route;
  }

  public String routeCode() {
    return route;
  }

  public UUID providerId() {
    return providerId;
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

  public BigDecimal effectiveReliability() {
    return effectiveReliability;
  }

  public BigDecimal rankingScore() {
    return rankingScore;
  }

  public int rankingPosition() {
    return rankingPosition;
  }

  public boolean recommended() {
    return recommended;
  }

  public Instant expiresAt() {
    return expiresAt;
  }
}
