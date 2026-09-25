package com.fluxpay.repository;

import com.fluxpay.beans.LedgerJournal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface LedgerJournalRepository extends Repository<LedgerJournal, String> {
  LedgerJournal saveAndFlush(LedgerJournal journal);

  Optional<LedgerJournal> findByJournalReference(String journalReference);

  List<LedgerJournal> findByJournalReferenceInOrderByJournalReferenceAsc(
      Collection<String> references);
}
