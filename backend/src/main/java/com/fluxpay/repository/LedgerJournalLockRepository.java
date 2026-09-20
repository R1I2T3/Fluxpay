package com.fluxpay.repository;

import com.fluxpay.beans.LedgerJournalLock;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface LedgerJournalLockRepository extends Repository<LedgerJournalLock, Integer> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select l from LedgerJournalLock l where l.lockId = :lockId")
  Optional<LedgerJournalLock> findByIdForUpdate(@Param("lockId") Integer lockId);
}
