package com.fluxpay.beans;

import com.fluxpay.domain.RailType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "transfer_providers")
public class TransferProvider {

  private static final String CODE_PATTERN = "[A-Z][A-Z0-9_]{2,49}";

  @Id
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "provider_code", nullable = false, unique = true, length = 50, updatable = false)
  private String providerCode;

  @Column(name = "provider_name", nullable = false, length = 100)
  private String providerName;

  @Enumerated(EnumType.STRING)
  @Column(name = "rail_type", nullable = false, length = 30)
  private RailType railType;

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

  protected TransferProvider() {}

  public static TransferProvider create(
      UUID id,
      String providerCode,
      String providerName,
      RailType railType,
      boolean active,
      boolean systemProtected,
      Instant now) {
    TransferProvider provider = new TransferProvider();
    provider.id = Objects.requireNonNull(id, "id must not be null");
    provider.providerCode = normalizeCode(providerCode);
    provider.providerName = requireText(providerName, "providerName");
    provider.railType = Objects.requireNonNull(railType, "railType must not be null");
    provider.active = active;
    provider.systemProtected = systemProtected;
    provider.version = 0L;
    provider.createdAt = Objects.requireNonNull(now, "now must not be null");
    provider.updatedAt = now;
    return provider;
  }

  public void archive(Instant now) {
    Instant archived = Objects.requireNonNull(now, "now must not be null");
    this.active = false;
    this.archivedAt = archived;
    this.updatedAt = archived;
  }

  static String normalizeCode(String value) {
    String normalized = requireText(value, "code").toUpperCase(Locale.ROOT);
    if (!normalized.matches(CODE_PATTERN)) {
      throw new IllegalArgumentException("code must match " + CODE_PATTERN);
    }
    return normalized;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value.trim();
  }

  public UUID id() {
    return id;
  }

  public String code() {
    return providerCode;
  }

  public String name() {
    return providerName;
  }

  public RailType railType() {
    return railType;
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

  public String getProviderCode() {
    return providerCode;
  }

  public String getProviderName() {
    return providerName;
  }

  public RailType getRailType() {
    return railType;
  }

  public boolean isActive() {
    return active;
  }

  public boolean isSystemProtected() {
    return systemProtected;
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
}
