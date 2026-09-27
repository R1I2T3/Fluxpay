package com.fluxpay.repository;

import com.fluxpay.beans.LedgerEntry;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Deliberately exposes insertion and reads, with no delete/update operations. */
public interface LedgerEntryRepository extends Repository<LedgerEntry, UUID> {
  LedgerEntry save(LedgerEntry entry);

  Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

  List<LedgerEntry> findByJournalReference(String journalReference);

  List<LedgerEntry> findByJournalReferenceInOrderByCreatedAtAscIdAsc(Collection<String> references);

  Page<LedgerEntry> findByWalletIdOrderByCreatedAtDescIdDesc(UUID walletId, Pageable pageable);
}
