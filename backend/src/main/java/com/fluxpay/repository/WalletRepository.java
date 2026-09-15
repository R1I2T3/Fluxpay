package com.fluxpay.repository;

import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface WalletRepository extends Repository<Wallet, UUID> {
  Wallet saveAndFlush(Wallet wallet);

  Optional<Wallet> findById(UUID id);

  Optional<Wallet> findByUserIdAndCurrencyAndAccountRole(
      UUID userId, String currency, WalletAccountRole accountRole);

  List<Wallet> findByUserIdAndAccountRoleOrderByCurrencyAsc(
      UUID userId, WalletAccountRole accountRole);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from Wallet w where w.id = :id")
  Optional<Wallet> findByIdForUpdate(@Param("id") UUID id);

  // Oracle RAW order matches canonical UUID text order, not Java UUID's signed compareTo.
  // Multi-wallet callers must use this same order within their posting transaction.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select w from Wallet w where w.id in :ids order by w.id")
  List<Wallet> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);
}
