package com.fluxpay.beans;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only persistence record; corrections create new entries. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {
  @Id
  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "wallet_id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID walletId;

  @Column(name = "entry_type", length = 10, nullable = false, updatable = false)
  private String entryType;

  @Column(name = "amount", precision = 19, scale = 4, nullable = false, updatable = false)
  private BigDecimal amount;

  @Column(name = "currency", length = 3, nullable = false, updatable = false)
  private String currency;

  @Column(name = "idempotency_key", length = 255, nullable = false, updatable = false)
  private String idempotencyKey;

  @Column(name = "journal_reference", length = 64, updatable = false)
  private String journalReference;

  @Column(name = "narration", length = 255, updatable = false)
  private String narration;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LedgerEntry() {}

  public LedgerEntry(
      UUID walletId,
      String entryType,
      BigDecimal amount,
      String currency,
      String idempotencyKey,
      String journalReference,
      String narration,
      Instant createdAt) {
    this.id = UUID.randomUUID();
    this.walletId = walletId;
    this.entryType = entryType;
    this.amount = amount;
    this.currency = currency;
    this.idempotencyKey = idempotencyKey;
    this.journalReference = journalReference;
    this.narration = narration;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getWalletId() {
    return walletId;
  }

  public String getEntryType() {
    return entryType;
  }

  public BigDecimal getAmount() {
    return amount.setScale(4);
  }

  public String getCurrency() {
    return currency;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getJournalReference() {
    return journalReference;
  }

  public String getNarration() {
    return narration;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
