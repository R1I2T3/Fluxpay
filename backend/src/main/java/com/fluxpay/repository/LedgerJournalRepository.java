package com.fluxpay.repository;

import com.fluxpay.beans.LedgerJournal;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface LedgerJournalRepository extends Repository<LedgerJournal, String> {
  LedgerJournal saveAndFlush(LedgerJournal journal);

  Optional<LedgerJournal> findByJournalReference(String journalReference);
}
