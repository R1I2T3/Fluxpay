package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Payout rail mapped to {@code payout_routes} exactly per V401 DDL.
 *
 * <p>Id strategy: the DDL declares {@code id RAW(16)} as UUID storage. The id is modelled as {@code
 * UUID} and Hibernate maps it to RAW(16) on Oracle. Business lookups use {@code routeCode}; callers
 * never parse the UUID.
 *
 * <p>{@code active} is {@code NUMBER(1)} on Oracle; it is mapped as {@code boolean} and the
 * Hibernate Oracle dialect persists it as 0/1.
 */
@Entity
@Table(name = "payout_routes")
public class PayoutRoute {

  @Id
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "route_code", nullable = false, unique = true, length = 50)
  private String routeCode;

  @Column(name = "route_name", nullable = false, length = 100)
  private String name;

  @Column(name = "provider_name", nullable = false, length = 100)
  private String providerName;

  @Column(name = "route_type", nullable = false, length = 30)
  private String routeType;

  @Column(name = "base_fee", nullable = false, precision = 19, scale = 4)
  private BigDecimal baseFee;

  @Column(name = "fx_spread_percentage", nullable = false, precision = 9, scale = 6)
  private BigDecimal fxSpreadPercentage;

  @Column(name = "estimated_minutes", nullable = false)
  private int estimatedMinutes;

  @Column(name = "success_rate", nullable = false, precision = 5, scale = 2)
  private BigDecimal successRate;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PayoutRoute() {}

  /**
   * Deterministic factory for domain tests only. Parses the decimal strings and sets {@code
   * active=true}, {@code version=0} and fixed timestamps.
   */
  public static PayoutRoute seed(
      UUID id,
      String code,
      String name,
      String providerName,
      String routeType,
      String baseFee,
      String fxSpreadPercentage,
      int estimatedMinutes,
      String successRate) {
    Instant fixed = Instant.parse("2026-01-01T00:00:00Z");
    PayoutRoute route = new PayoutRoute();
    route.id = id;
    route.routeCode = code;
    route.name = name;
    route.providerName = providerName;
    route.routeType = routeType;
    route.baseFee = new BigDecimal(baseFee);
    route.fxSpreadPercentage = new BigDecimal(fxSpreadPercentage);
    route.estimatedMinutes = estimatedMinutes;
    route.successRate = new BigDecimal(successRate);
    route.active = true;
    route.version = 0L;
    route.createdAt = fixed;
    route.updatedAt = fixed;
    return route;
  }

  public void update(
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal successRate,
      boolean active) {
    if (baseFee.signum() < 0
        || fxSpreadPercentage.signum() < 0
        || estimatedMinutes <= 0
        || successRate.signum() < 0
        || successRate.compareTo(new BigDecimal("100")) > 0) {
      throw new IllegalArgumentException("invalid route metrics");
    }
    this.baseFee = baseFee;
    this.fxSpreadPercentage = fxSpreadPercentage;
    this.estimatedMinutes = estimatedMinutes;
    this.successRate = successRate;
    this.active = active;
    this.updatedAt = Instant.now();
  }

  /** String overload so concise domain tests can pass decimal literals directly. */
  public void update(
      String baseFee,
      String fxSpreadPercentage,
      int estimatedMinutes,
      String successRate,
      boolean active) {
    update(
        new BigDecimal(baseFee),
        new BigDecimal(fxSpreadPercentage),
        estimatedMinutes,
        new BigDecimal(successRate),
        active);
  }

  public UUID getId() {
    return id;
  }

  public String getRouteCode() {
    return routeCode;
  }

  public String getName() {
    return name;
  }

  public String getProviderName() {
    return providerName;
  }

  public String getRouteType() {
    return routeType;
  }

  public BigDecimal getBaseFee() {
    return baseFee;
  }

  public BigDecimal getFxSpreadPercentage() {
    return fxSpreadPercentage;
  }

  public int getEstimatedMinutes() {
    return estimatedMinutes;
  }

  public BigDecimal getSuccessRate() {
    return successRate;
  }

  public boolean isActive() {
    return active;
  }

  public Long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Record-style aliases used by the recommendation formula and PRD quote shape. */
  public UUID id() {
    return id;
  }

  public String code() {
    return routeCode;
  }

  public String name() {
    return name;
  }

  public String providerName() {
    return providerName;
  }

  public String routeType() {
    return routeType;
  }

  public BigDecimal baseFee() {
    return baseFee;
  }

  public BigDecimal fxSpreadPercentage() {
    return fxSpreadPercentage;
  }

  public int estimatedMinutes() {
    return estimatedMinutes;
  }

  public BigDecimal successRate() {
    return successRate;
  }

  public boolean active() {
    return active;
  }
}
