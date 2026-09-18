package com.fluxpay.repository;

import com.fluxpay.beans.BankAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface BankAccountRepository extends Repository<BankAccount, UUID> {
  BankAccount save(BankAccount account);

  java.util.Optional<BankAccount> findById(UUID id);

  List<BankAccount> findByUserId(UUID userId);
}
