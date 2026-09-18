package com.fluxpay.repository;

import com.fluxpay.beans.BankAccount;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

public interface BankAccountRepository extends Repository<BankAccount, UUID> {
  BankAccount save(BankAccount account);

  List<BankAccount> findByUserId(UUID userId);
}
