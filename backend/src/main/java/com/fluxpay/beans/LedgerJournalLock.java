package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_journal_locks")
public class LedgerJournalLock {
  @Id
  @Column(name = "lock_id", precision = 3, nullable = false, updatable = false)
  private Integer lockId;

  protected LedgerJournalLock() {}

  public Integer getLockId() {
    return lockId;
  }
}
