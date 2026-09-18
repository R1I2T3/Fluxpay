package com.fluxpay.service;

import com.fluxpay.beans.LedgerEntry;
import com.fluxpay.beans.LedgerJournal;
import com.fluxpay.beans.LedgerTransactionCategory;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.exception.LedgerIdempotencyConflictException;
import com.fluxpay.repository.LedgerEntryRepository;
import com.fluxpay.repository.LedgerJournalLockRepository;
import com.fluxpay.repository.LedgerJournalRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates and posts one complete journal inside a single database transaction. */
@Service
public class LedgerJournalService {
  private static final Set<String> ENTRY_TYPES = Set.of("DEBIT", "CREDIT");
  private static final Set<String> CURRENCIES = Set.of("USD", "EUR", "INR");

  private final LedgerWriter writer;
  private final LedgerPostingContext context;
  private final LedgerJournalRepository journals;
  private final LedgerJournalLockRepository journalLocks;
  private final LedgerEntryRepository entries;
  private final Clock clock;

  public LedgerJournalService(
      LedgerWriter writer,
      LedgerPostingContext context,
      LedgerJournalRepository journals,
      LedgerJournalLockRepository journalLocks,
      LedgerEntryRepository entries,
      Clock clock) {
    this.writer = writer;
    this.context = context;
    this.journals = journals;
    this.journalLocks = journalLocks;
    this.entries = entries;
    this.clock = clock;
  }

  @Transactional
  public void post(String journalReference, List<LedgerJournalLine> lines) {
    post(journalReference, LedgerTransactionCategory.LEGACY, lines);
  }

  @Transactional
  public void post(
      String journalReference,
      LedgerTransactionCategory transactionCategory,
      List<LedgerJournalLine> lines) {
    validateText(journalReference, "journalReference", 64);
    Objects.requireNonNull(transactionCategory, "transactionCategory");
    if (lines == null || lines.isEmpty()) {
      throw new IllegalArgumentException("A journal requires at least one line");
    }

    Map<String, Totals> totalsByCurrency = new HashMap<>();
    Map<String, LedgerPostingContext.EntryMetadata> metadataByKey = new HashMap<>();
    Set<String> keys = new HashSet<>();
    for (LedgerJournalLine line : lines) {
      validateLine(line);
      if (!keys.add(line.idempotencyKey())) {
        throw new IllegalArgumentException(
            "Duplicate ledger idempotency key: " + line.idempotencyKey());
      }
      if (line.narration() != null && line.narration().length() > 255) {
        throw new IllegalArgumentException("narration must not exceed 255 characters");
      }
      metadataByKey.put(
          line.idempotencyKey(),
          new LedgerPostingContext.EntryMetadata(
              journalReference, line.narration(), normalizedRate(line.rate()), line.quoteId()));
      Totals totals = totalsByCurrency.computeIfAbsent(line.currency(), ignored -> new Totals());
      if ("DEBIT".equals(line.entryType())) {
        totals.debits = totals.debits.add(line.amount());
      } else {
        totals.credits = totals.credits.add(line.amount());
      }
    }

    totalsByCurrency.forEach(
        (currency, totals) -> {
          if (totals.debits.compareTo(totals.credits) != 0) {
            throw new IllegalArgumentException(
                "Journal is not balanced for "
                    + currency
                    + ": debits="
                    + totals.debits
                    + ", credits="
                    + totals.credits);
          }
        });

    String payloadHash = payloadHash(transactionCategory, lines);
    int lockId = Math.floorMod(journalReference.hashCode(), 64);
    journalLocks
        .findByIdForUpdate(lockId)
        .orElseThrow(() -> new IllegalStateException("Missing ledger journal lock " + lockId));
    LedgerJournal existing = journals.findByJournalReference(journalReference).orElse(null);
    if (existing != null) {
      if (existing.getTransactionCategory() != transactionCategory
          || (existing.getPayloadHash() != null
              && !existing.getPayloadHash().equals(payloadHash))
          || (existing.getPayloadHash() == null
              && !historicalPayloadMatches(journalReference, transactionCategory, lines))) {
        throw new LedgerIdempotencyConflictException(journalReference);
      }
      return;
    }
    journals.saveAndFlush(
        new LedgerJournal(journalReference, transactionCategory, payloadHash, clock.instant()));

    List<LedgerJournalLine> postingOrder =
        lines.stream().sorted(Comparator.comparing(line -> line.walletId().toString())).toList();
    try (LedgerPostingContext.Scope ignored = context.bind(metadataByKey)) {
      for (LedgerJournalLine line : postingOrder) {
        writer.append(
            line.walletId(),
            line.entryType(),
            line.amount(),
            line.currency(),
            line.idempotencyKey());
      }
    }
  }

  private static void validateLine(LedgerJournalLine line) {
    Objects.requireNonNull(line, "journal line");
    Objects.requireNonNull(line.walletId(), "walletId");
    if (!ENTRY_TYPES.contains(line.entryType())) {
      throw new IllegalArgumentException("entryType must be DEBIT or CREDIT");
    }
    if (!CURRENCIES.contains(line.currency())) {
      throw new IllegalArgumentException("Unsupported currency: " + line.currency());
    }
    validateText(line.idempotencyKey(), "idempotencyKey", 255);
    BigDecimal amount = Objects.requireNonNull(line.amount(), "amount");
    if (amount.signum() <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
    if (amount.scale() > 4) {
      throw new IllegalArgumentException("amount must have at most four decimal places");
    }
    if (amount.setScale(4).precision() > 19) {
      throw new IllegalArgumentException("amount exceeds NUMBER(19,4)");
    }
    if ((line.rate() == null) != (line.quoteId() == null)) {
      throw new IllegalArgumentException("rate and quoteId must both be present or both be absent");
    }
    if (line.rate() != null) {
      if (line.rate().signum() <= 0 || line.rate().scale() > 8) {
        throw new IllegalArgumentException("rate must be positive with at most eight decimal places");
      }
      if (line.rate().setScale(8).precision() > 19) {
        throw new IllegalArgumentException("rate exceeds NUMBER(19,8)");
      }
      validateText(line.quoteId(), "quoteId", 36);
    }
  }

  private boolean historicalPayloadMatches(
      String journalReference,
      LedgerTransactionCategory transactionCategory,
      List<LedgerJournalLine> requested) {
    if (transactionCategory != LedgerTransactionCategory.LEGACY) {
      return false;
    }
    List<String> stored =
        entries.findByJournalReference(journalReference).stream()
            .map(LedgerJournalService::canonicalLine)
            .sorted()
            .toList();
    List<String> replayed = requested.stream().map(LedgerJournalService::canonicalLine).sorted().toList();
    return stored.equals(replayed);
  }

  private static String payloadHash(
      LedgerTransactionCategory transactionCategory, List<LedgerJournalLine> lines) {
    List<String> canonical = new ArrayList<>();
    canonical.add(transactionCategory.name());
    lines.stream().map(LedgerJournalService::canonicalLine).sorted().forEach(canonical::add);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String value : canonical) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String canonicalLine(LedgerJournalLine line) {
    return encodeFields(
        line.walletId().toString(),
        line.entryType(),
        line.amount().setScale(4).toPlainString(),
        line.currency(),
        line.idempotencyKey(),
        nullToEmpty(line.narration()),
        line.rate() == null ? "" : normalizedRate(line.rate()).toPlainString(),
        nullToEmpty(line.quoteId()));
  }

  private static String canonicalLine(LedgerEntry entry) {
    return encodeFields(
        entry.getWalletId().toString(),
        entry.getEntryType(),
        entry.getAmount().setScale(4).toPlainString(),
        entry.getCurrency(),
        entry.getIdempotencyKey(),
        nullToEmpty(entry.getNarration()),
        entry.getRate() == null ? "" : normalizedRate(entry.getRate()).toPlainString(),
        nullToEmpty(entry.getQuoteId()));
  }

  private static String encodeFields(String... fields) {
    StringBuilder encoded = new StringBuilder();
    for (String field : fields) {
      byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
      encoded.append(bytes.length).append(':').append(java.util.Base64.getEncoder().encodeToString(bytes));
    }
    return encoded.toString();
  }

  private static BigDecimal normalizedRate(BigDecimal rate) {
    return rate == null ? null : rate.setScale(8);
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static void validateText(String value, String name, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must not exceed " + maximumLength + " characters");
    }
  }

  private static final class Totals {
    private BigDecimal debits = BigDecimal.ZERO;
    private BigDecimal credits = BigDecimal.ZERO;
  }
}
