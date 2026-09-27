package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Immutable
@Table(name = "ledger_journals")
public class LedgerJournal {
  @Id
  @Column(name = "journal_reference", length = 64, nullable = false, updatable = false)
  private String journalReference;

  @Enumerated(EnumType.STRING)
  @Column(name = "transaction_category", length = 24, nullable = false, updatable = false)
  private LedgerTransactionCategory transactionCategory;

  @Column(name = "payload_hash", columnDefinition = "CHAR(64)", length = 64, updatable = false)
  private String payloadHash;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LedgerJournal() {}

  public LedgerJournal(
      String journalReference,
      LedgerTransactionCategory transactionCategory,
      String payloadHash,
      Instant createdAt) {
    this.journalReference = Objects.requireNonNull(journalReference);
    this.transactionCategory = Objects.requireNonNull(transactionCategory);
    this.payloadHash = Objects.requireNonNull(payloadHash);
    this.createdAt = Objects.requireNonNull(createdAt);
  }

  public static LedgerJournal historical(
      String journalReference, LedgerTransactionCategory transactionCategory, Instant createdAt) {
    LedgerJournal journal = new LedgerJournal();
    journal.journalReference = Objects.requireNonNull(journalReference);
    journal.transactionCategory = Objects.requireNonNull(transactionCategory);
    journal.createdAt = Objects.requireNonNull(createdAt);
    return journal;
  }

  public String getJournalReference() {
    return journalReference;
  }

  public LedgerTransactionCategory getTransactionCategory() {
    return transactionCategory;
  }

  public String getPayloadHash() {
    return payloadHash;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
