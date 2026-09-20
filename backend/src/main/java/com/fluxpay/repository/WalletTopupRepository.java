package com.fluxpay.repository;

import com.fluxpay.beans.LedgerJournal;
import com.fluxpay.beans.LedgerTransactionCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Posted top-up totals; callers must hold the customer wallet's posting lock. */
public interface WalletTopupRepository extends Repository<LedgerJournal, String> {
  @Query(
      "select coalesce(sum(e.amount), 0) from LedgerEntry e, LedgerJournal j "
          + "where e.journalReference = j.journalReference and e.walletId = :walletId "
          + "and e.entryType = 'CREDIT' and j.transactionCategory = :category "
          + "and j.createdAt >= :start and j.createdAt < :end")
  BigDecimal sumCreditsForDay(
      @Param("walletId") UUID walletId,
      @Param("category") LedgerTransactionCategory category,
      @Param("start") Instant start,
      @Param("end") Instant end);
}
