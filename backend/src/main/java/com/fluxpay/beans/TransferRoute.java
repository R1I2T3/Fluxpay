package com.fluxpay.beans;

import com.fluxpay.domain.DestinationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Administrator-managed commercial route mapped to {@code transfer_routes}. */
@Entity
@Table(name = "transfer_routes")
public class TransferRoute {
  @Id
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "provider_id", nullable = false)
  private TransferProvider provider;

  @Column(name = "route_code", nullable = false, unique = true, length = 50, updatable = false)
  private String routeCode;

  @Column(name = "route_name", nullable = false, length = 100)
  private String name;

  @Enumerated(EnumType.STRING)
  @Column(name = "destination_type", nullable = false, length = 30)
  private DestinationType destinationType;

  @Column(name = "destination_country", length = 2)
  private String destinationCountry;

  @Column(name = "payout_currency", nullable = false, length = 3)
  private String payoutCurrency;

  @Column(name = "base_fee", nullable = false, precision = 19, scale = 4)
  private BigDecimal baseFee;

  @Column(name = "fx_spread_percentage", nullable = false, precision = 9, scale = 6)
  private BigDecimal fxSpreadPercentage;

  @Column(name = "estimated_minutes", nullable = false)
  private int estimatedMinutes;

  @Column(name = "configured_success_rate", nullable = false, precision = 5, scale = 2)
  private BigDecimal configuredSuccessRate;

  @Column(name = "minimum_recipient_amount", precision = 19, scale = 4)
  private BigDecimal minimumRecipientAmount;

  @Column(name = "maximum_recipient_amount", precision = 19, scale = 4)
  private BigDecimal maximumRecipientAmount;

  @Column(name = "active", nullable = false)
  private boolean active;

  @Column(name = "system_protected", nullable = false)
  private boolean systemProtected;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  @Transient private String legacyProviderName;
  @Transient private String legacyRouteType;

  protected TransferRoute() {}

  public static TransferRoute create(
      UUID id,
      TransferProvider provider,
      String routeCode,
      String name,
      DestinationType destinationType,
      String destinationCountry,
      String payoutCurrency,
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal configuredSuccessRate,
      BigDecimal minimumRecipientAmount,
      BigDecimal maximumRecipientAmount,
      boolean active,
      boolean systemProtected,
      Instant now) {
    TransferRoute route = new TransferRoute();
    route.id = Objects.requireNonNull(id, "id must not be null");
    route.provider = Objects.requireNonNull(provider, "provider must not be null");
    route.routeCode = TransferProvider.normalizeCode(routeCode);
    route.name = requireText(name, "name");
    route.destinationType =
        Objects.requireNonNull(destinationType, "destinationType must not be null");
    route.destinationCountry = normalizeCountry(destinationCountry, destinationType);
    route.payoutCurrency = normalizeCurrency(payoutCurrency);
    validateCommercials(
        baseFee,
        fxSpreadPercentage,
        estimatedMinutes,
        configuredSuccessRate,
        minimumRecipientAmount,
        maximumRecipientAmount);
    route.baseFee = baseFee;
    route.fxSpreadPercentage = fxSpreadPercentage;
    route.estimatedMinutes = estimatedMinutes;
    route.configuredSuccessRate = configuredSuccessRate;
    route.minimumRecipientAmount = minimumRecipientAmount;
    route.maximumRecipientAmount = maximumRecipientAmount;
    route.active = active;
    route.systemProtected = systemProtected;
    route.version = 0L;
    route.createdAt = Objects.requireNonNull(now, "now must not be null");
    route.updatedAt = now;
    return route;
  }

  /**
   * Mock-only fixture for unit tests with mocked repositories. Never persist: the legacy provider
   * name/route type are {@code @Transient} and lost on a JPA round-trip, and every seed synthesizes
   * the same {@code LEGACY_PROVIDER} code. DB-backed callers must save a {@link TransferProvider}
   * via {@code TransferProvider.create(...)} and use {@link #create}.
   */
  public static TransferRoute seed(
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
    TransferProvider provider =
        TransferProvider.create(
            UUID.nameUUIDFromBytes(("legacy:" + providerName).getBytes(StandardCharsets.UTF_8)),
            "LEGACY_PROVIDER",
            providerName,
            com.fluxpay.domain.RailType.BANK_NETWORK,
            true,
            false,
            fixed);
    TransferRoute route =
        create(
            id,
            provider,
            code,
            name,
            DestinationType.EXTERNAL_ACCOUNT,
            "ZZ",
            "USD",
            new BigDecimal(baseFee),
            new BigDecimal(fxSpreadPercentage),
            estimatedMinutes,
            new BigDecimal(successRate),
            null,
            null,
            true,
            false,
            fixed);
    route.legacyProviderName = providerName;
    route.legacyRouteType = routeType;
    return route;
  }

  public void update(
      TransferProvider provider,
      String name,
      DestinationType destinationType,
      String destinationCountry,
      String payoutCurrency,
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal configuredSuccessRate,
      BigDecimal minimumRecipientAmount,
      BigDecimal maximumRecipientAmount,
      boolean active,
      Instant now) {
    this.provider = Objects.requireNonNull(provider, "provider must not be null");
    this.name = requireText(name, "name");
    this.destinationType =
        Objects.requireNonNull(destinationType, "destinationType must not be null");
    this.destinationCountry = normalizeCountry(destinationCountry, destinationType);
    this.payoutCurrency = normalizeCurrency(payoutCurrency);
    validateCommercials(
        baseFee,
        fxSpreadPercentage,
        estimatedMinutes,
        configuredSuccessRate,
        minimumRecipientAmount,
        maximumRecipientAmount);
    this.baseFee = baseFee;
    this.fxSpreadPercentage = fxSpreadPercentage;
    this.estimatedMinutes = estimatedMinutes;
    this.configuredSuccessRate = configuredSuccessRate;
    this.minimumRecipientAmount = minimumRecipientAmount;
    this.maximumRecipientAmount = maximumRecipientAmount;
    this.active = active;
    this.updatedAt = Objects.requireNonNull(now, "now must not be null");
  }

  public void update(
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal successRate,
      boolean active) {
    validateCommercials(
        baseFee,
        fxSpreadPercentage,
        estimatedMinutes,
        successRate,
        minimumRecipientAmount,
        maximumRecipientAmount);
    this.baseFee = baseFee;
    this.fxSpreadPercentage = fxSpreadPercentage;
    this.estimatedMinutes = estimatedMinutes;
    this.configuredSuccessRate = successRate;
    this.active = active;
    this.updatedAt = Instant.now();
  }

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

  public void archive(Instant now) {
    Instant archived = Objects.requireNonNull(now, "now must not be null");
    active = false;
    archivedAt = archived;
    updatedAt = archived;
  }

  private static void validateCommercials(
      BigDecimal baseFee,
      BigDecimal spread,
      int eta,
      BigDecimal successRate,
      BigDecimal minimum,
      BigDecimal maximum) {
    if (baseFee == null
        || baseFee.signum() < 0
        || spread == null
        || spread.signum() < 0
        || eta <= 0
        || successRate == null
        || successRate.signum() < 0
        || successRate.compareTo(new BigDecimal("100")) > 0
        || (minimum != null && minimum.signum() <= 0)
        || (maximum != null && maximum.signum() <= 0)
        || (minimum != null && maximum != null && maximum.compareTo(minimum) < 0)) {
      throw new IllegalArgumentException("invalid route metrics");
    }
  }

  private static String normalizeCountry(String country, DestinationType destinationType) {
    if (country == null || country.isBlank()) {
      if (destinationType == DestinationType.EXTERNAL_ACCOUNT)
        throw new IllegalArgumentException("destinationCountry is required for external routes");
      return null;
    }
    String normalized = country.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{2}"))
      throw new IllegalArgumentException("destinationCountry must be ISO-3166 alpha-2");
    return normalized;
  }

  private static String normalizeCurrency(String currency) {
    String normalized = requireText(currency, "payoutCurrency").toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z]{3}"))
      throw new IllegalArgumentException("payoutCurrency must be ISO-4217");
    return normalized;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(name + " must not be blank");
    return value.trim();
  }

  public UUID id() {
    return id;
  }

  public String code() {
    return routeCode;
  }

  public String name() {
    return name;
  }

  public TransferProvider provider() {
    return provider;
  }

  public DestinationType destinationType() {
    return destinationType;
  }

  public String destinationCountry() {
    return destinationCountry;
  }

  public String payoutCurrency() {
    return payoutCurrency;
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
    return configuredSuccessRate;
  }

  public BigDecimal configuredSuccessRate() {
    return configuredSuccessRate;
  }

  public BigDecimal minimumRecipientAmount() {
    return minimumRecipientAmount;
  }

  public BigDecimal maximumRecipientAmount() {
    return maximumRecipientAmount;
  }

  public boolean active() {
    return active;
  }

  public boolean systemProtected() {
    return systemProtected;
  }

  public Long version() {
    return version;
  }

  public Instant archivedAt() {
    return archivedAt;
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

  public TransferProvider getProvider() {
    return provider;
  }

  public String getProviderName() {
    return legacyProviderName != null ? legacyProviderName : provider.name();
  }

  public String getRouteType() {
    return legacyRouteType != null ? legacyRouteType : provider.railType().name();
  }

  public DestinationType getDestinationType() {
    return destinationType;
  }

  public String getDestinationCountry() {
    return destinationCountry;
  }

  public String getPayoutCurrency() {
    return payoutCurrency;
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
    return configuredSuccessRate;
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

  public Instant getArchivedAt() {
    return archivedAt;
  }

  public String providerName() {
    return getProviderName();
  }

  public String routeType() {
    return getRouteType();
  }
}
