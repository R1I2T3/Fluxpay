package com.fluxpay.service;

import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.dto.LedgerEntryResponse;
import com.fluxpay.dto.LedgerPageResponse;
import com.fluxpay.dto.WalletSummaryResponse;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.WalletRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WalletQueryService {
  private static final int MAX_PAGE_SIZE = 100;

  private final WalletRepository wallets;
  private final LedgerEntryRepository entries;

  public WalletQueryService(WalletRepository wallets, LedgerEntryRepository entries) {
    this.wallets = wallets;
    this.entries = entries;
  }

  public List<WalletSummaryResponse> wallets(UUID userId) {
    UUID owner = requireUser(userId);
    return wallets
        .findByUserIdAndAccountRoleOrderByCurrencyAsc(owner, WalletAccountRole.CUSTOMER)
        .stream()
        .map(WalletQueryService::walletResponse)
        .toList();
  }

  public LedgerPageResponse ledger(UUID userId, UUID walletId, int page, int size) {
    UUID owner = requireUser(userId);
    if (walletId == null) {
      throw new WalletNotFoundException();
    }
    validatePage(page, size);
    Wallet wallet =
        wallets
            .findById(walletId)
            .filter(candidate -> visibleTo(candidate, owner))
            .orElseThrow(WalletNotFoundException::new);
    Page<LedgerEntry> result =
        entries.findByWalletIdOrderByCreatedAtDescIdDesc(
            wallet.getId(), PageRequest.of(page, size));
    return new LedgerPageResponse(
        result.getContent().stream().map(WalletQueryService::entryResponse).toList(),
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  private static boolean visibleTo(Wallet wallet, UUID owner) {
    return owner.equals(wallet.getUserId())
        && wallet.getAccountRole() == WalletAccountRole.CUSTOMER;
  }

  private static UUID requireUser(UUID userId) {
    if (userId == null) {
      throw new IllegalArgumentException("Authenticated user is required");
    }
    return userId;
  }

  private static void validatePage(int page, int size) {
    if (page < 0) {
      throw new IllegalArgumentException("Page must be zero or greater");
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("Page size must be between 1 and 100");
    }
  }

  private static WalletSummaryResponse walletResponse(Wallet wallet) {
    return new WalletSummaryResponse(
        wallet.getId().toString(),
        wallet.getCurrency(),
        wallet.getHeldBalance().toPlainString(),
        wallet.getAvailableBalance().toPlainString());
  }

  private static LedgerEntryResponse entryResponse(LedgerEntry entry) {
    return new LedgerEntryResponse(
        entry.getId().toString(),
        entry.getEntryType(),
        entry.getAmount().toPlainString(),
        entry.getCurrency(),
        entry.getJournalReference(),
        entry.getNarration(),
        entry.getCreatedAt().toString());
  }
}
