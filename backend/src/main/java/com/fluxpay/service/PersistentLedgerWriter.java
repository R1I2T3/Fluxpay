package com.fluxpay.service;

import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.exception.InsufficientWalletFundsException;
import com.fluxpay.exception.LedgerIdempotencyConflictException;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Oracle-backed writer and the sole runtime mutation path for posted wallet balances. */
@Service
@Primary
public class PersistentLedgerWriter implements LedgerWriter {
  private static final Set<String> ENTRY_TYPES = Set.of("DEBIT", "CREDIT");
  private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "INR");

  private final WalletRepository wallets;
  private final LedgerEntryRepository entries;
  private final LedgerPostingContext context;
  private final java.time.Clock clock;

  public PersistentLedgerWriter(
      WalletRepository wallets,
      LedgerEntryRepository entries,
      LedgerPostingContext context,
      java.time.Clock clock) {
    this.wallets = wallets;
    this.entries = entries;
    this.context = context;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public boolean contains(String idempotencyKey) {
    return entries.findByIdempotencyKey(idempotencyKey).isPresent();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      UUID walletId, String entryType, BigDecimal amount, String currency, String idempotencyKey) {
    BigDecimal postedAmount = validate(walletId, entryType, amount, currency, idempotencyKey);
    LedgerPostingContext.EntryMetadata metadata = metadata(idempotencyKey);

    LedgerEntry existing = entries.findByIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      verifyReplay(existing, walletId, entryType, postedAmount, currency, metadata);
      context.record(LedgerPostingContext.PostingResult.REPLAY);
      return;
    }

    Wallet wallet =
        wallets
            .findByIdForUpdate(walletId)
            .orElseThrow(() -> new NoSuchElementException("Wallet not found: " + walletId));
    existing = entries.findByIdempotencyKey(idempotencyKey).orElse(null);
    if (existing != null) {
      verifyReplay(existing, walletId, entryType, postedAmount, currency, metadata);
      context.record(LedgerPostingContext.PostingResult.REPLAY);
      return;
    }
    if (!currency.equals(wallet.getCurrency())) {
      throw new IllegalArgumentException(
          "Ledger currency "
              + currency
              + " does not match wallet currency "
              + wallet.getCurrency());
    }

    BigDecimal updatedBalance;
    if ("DEBIT".equals(entryType)) {
      boolean reserved =
          context.consumeReservation(idempotencyKey, walletId, currency, postedAmount);
      if (reserved) {
        if (wallet.getHeldBalance().compareTo(postedAmount) < 0) {
          throw new IllegalStateException("Reserved wallet funds are no longer present");
        }
        wallet.setHeldBalance(wallet.getHeldBalance().subtract(postedAmount));
      } else {
        if (wallet.getAccountRole() == WalletAccountRole.CUSTOMER
            && wallet.getAvailableBalance().compareTo(postedAmount) < 0) {
          throw new InsufficientWalletFundsException(walletId);
        }
      }
      updatedBalance = wallet.getBalance().subtract(postedAmount);
    } else {
      updatedBalance = wallet.getBalance().add(postedAmount);
    }

    context.record(LedgerPostingContext.PostingResult.NEW);
    wallet.setBalance(updatedBalance);
    entries.save(
        new LedgerEntry(
            walletId,
            entryType,
            postedAmount,
            currency,
            idempotencyKey,
            metadata == null ? null : metadata.journalReference(),
            metadata == null ? null : metadata.narration(),
            metadata == null ? null : metadata.rate(),
            metadata == null ? null : metadata.quoteId(),
            clock.instant()));
    wallets.saveAndFlush(wallet);
  }

  private LedgerPostingContext.EntryMetadata metadata(String idempotencyKey) {
    LedgerPostingContext.EntryMetadata metadata = context.current(idempotencyKey);
    if (context.isBound() && metadata == null) {
      throw new IllegalStateException(
          "Active journal has no metadata for ledger key " + idempotencyKey);
    }
    return metadata;
  }

  private static BigDecimal validate(
      UUID walletId, String entryType, BigDecimal amount, String currency, String idempotencyKey) {
    Objects.requireNonNull(walletId, "walletId");
    if (!ENTRY_TYPES.contains(entryType)) {
      throw new IllegalArgumentException("entryType must be DEBIT or CREDIT");
    }
    if (!CURRENCIES.contains(currency)) {
      throw new IllegalArgumentException("Unsupported currency: " + currency);
    }
    if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255) {
      throw new IllegalArgumentException("idempotencyKey must contain 1 to 255 characters");
    }
    Objects.requireNonNull(amount, "amount");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    if (amount.scale() > 4) {
      throw new IllegalArgumentException("amount must have at most four decimal places");
    }
    BigDecimal postedAmount = amount.setScale(4);
    if (postedAmount.precision() > 19) {
      throw new IllegalArgumentException("amount exceeds NUMBER(19,4)");
    }
    return postedAmount;
  }

  private static void verifyReplay(
      LedgerEntry existing,
      UUID walletId,
      String entryType,
      BigDecimal amount,
      String currency,
      LedgerPostingContext.EntryMetadata metadata) {
    boolean samePayload =
        existing.getWalletId().equals(walletId)
            && existing.getEntryType().equals(entryType)
            && existing.getAmount().compareTo(amount) == 0
            && existing.getCurrency().equals(currency);
    boolean sameMetadata =
        metadata == null
            || (Objects.equals(existing.getJournalReference(), metadata.journalReference())
                && Objects.equals(existing.getNarration(), metadata.narration())
                && decimalEquals(existing.getRate(), metadata.rate())
                && Objects.equals(existing.getQuoteId(), metadata.quoteId()));
    if (!samePayload || !sameMetadata) {
      throw new LedgerIdempotencyConflictException(existing.getIdempotencyKey());
    }
  }

  private static boolean decimalEquals(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }
}
