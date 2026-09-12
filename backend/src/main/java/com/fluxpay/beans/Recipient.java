package com.fluxpay.beans;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "recipients")
public class Recipient {
  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false)
  private String name;

  @Column(name = "account_ref", nullable = false)
  private String account;

  @Column(name = "bank_name")
  private String bankName;

  private String country;
  private String currency;

  @Enumerated(EnumType.STRING)
  private RecipientStatus status;

  @Column(name = "profile_complete")
  private boolean profileComplete;

  @Version private long version;

  @Column(name = "created_at")
  private Instant createdAt;

  @Column(name = "updated_at")
  private Instant updatedAt;

  protected Recipient() {}

  public Recipient(
      UUID id,
      UUID userId,
      String name,
      String account,
      String bankName,
      String country,
      String currency,
      RecipientStatus status,
      Instant now) {
    this.id = id;
    this.userId = userId;
    this.name = name;
    this.account = account;
    this.bankName = bankName;
    this.country = country;
    this.currency = currency;
    this.status = status;
    this.profileComplete =
        country != null && currency != null && !country.isBlank() && !currency.isBlank();
    this.createdAt = now;
    this.updatedAt = now;
  }

  public UUID id() {
    return id;
  }

  public UUID userId() {
    return userId;
  }

  public String name() {
    return name;
  }

  public String account() {
    return account;
  }

  public String bankName() {
    return bankName;
  }

  public String country() {
    return country;
  }

  public String currency() {
    return currency;
  }

  public RecipientStatus status() {
    return status;
  }

  public long version() {
    return version;
  }

  public void update(
      String name,
      String account,
      String bank,
      String country,
      String currency,
      RecipientStatus status,
      Instant now) {
    this.name = name;
    this.account = account;
    this.bankName = bank;
    this.country = country;
    this.currency = currency;
    this.status = status;
    this.profileComplete =
        country != null && currency != null && !country.isBlank() && !currency.isBlank();
    this.updatedAt = now;
  }

  public boolean eligible() {
    return profileComplete && status == RecipientStatus.ACTIVE;
  }
}
