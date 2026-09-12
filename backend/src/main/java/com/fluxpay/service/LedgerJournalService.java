package com.fluxpay.service;

import com.fluxpay.common.contracts.LedgerWriter;
import java.math.BigDecimal;
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

  public LedgerJournalService(LedgerWriter writer, LedgerPostingContext context) {
    this.writer = writer;
    this.context = context;
  }

  @Transactional
  public void post(String journalReference, List<LedgerJournalLine> lines) {
    validateText(journalReference, "journalReference", 64);
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
          new LedgerPostingContext.EntryMetadata(journalReference, line.narration()));
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
