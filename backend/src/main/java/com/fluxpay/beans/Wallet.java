package com.fluxpay.beans;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "wallets")
public class Wallet {
  @Id
  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "user_id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "currency", length = 3, nullable = false, updatable = false)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "account_role", length = 20, nullable = false, updatable = false)
  private WalletAccountRole accountRole;

  @Column(name = "balance", precision = 19, scale = 4, nullable = false)
  private BigDecimal balance = new BigDecimal("0.0000");

  @Column(name = "held_balance", precision = 19, scale = 4, nullable = false)
  private BigDecimal heldBalance = new BigDecimal("0.0000");

  @Version
  @Column(name = "version", precision = 10, scale = 0, nullable = false)
  private Long version;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Wallet() {}

  public Wallet(UUID userId, String currency, WalletAccountRole accountRole) {
    this.id = UUID.randomUUID();
    this.userId = Objects.requireNonNull(userId);
    this.currency = Objects.requireNonNull(currency);
    this.accountRole = Objects.requireNonNull(accountRole);
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getCurrency() {
    return currency;
  }

  public WalletAccountRole getAccountRole() {
    return accountRole;
  }

  // Oracle may omit trailing zeros when reading NUMBER; restore the monetary scale in Java.
  public BigDecimal getBalance() {
    return balance.setScale(4);
  }

  public BigDecimal getHeldBalance() {
    return heldBalance.setScale(4);
  }

  public BigDecimal getAvailableBalance() {
    return getBalance().subtract(getHeldBalance());
  }

  public Long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Runtime posting services must route posted balance changes through LedgerWriter. */
  public void setBalance(BigDecimal balance) {
    this.balance = Objects.requireNonNull(balance);
  }

  public void setHeldBalance(BigDecimal heldBalance) {
    this.heldBalance = Objects.requireNonNull(heldBalance);
  }
}
