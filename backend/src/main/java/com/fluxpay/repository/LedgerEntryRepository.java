package com.fluxpay.repository;

import com.fluxpay.beans.LedgerEntry;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Deliberately exposes insertion and reads, with no delete/update operations. */
public interface LedgerEntryRepository extends Repository<LedgerEntry, UUID> {
  LedgerEntry save(LedgerEntry entry);

  Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey);

  Page<LedgerEntry> findByWalletIdOrderByCreatedAtDescIdDesc(UUID walletId, Pageable pageable);
}
