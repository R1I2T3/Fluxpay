package com.fluxpay.service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Synchronous, transaction-scoped metadata used without changing the frozen LedgerWriter API. */
@Component
public class LedgerPostingContext {
  private final ThreadLocal<State> current = new ThreadLocal<>();

  EntryMetadata current(String idempotencyKey) {
    State state = current.get();
    return state == null ? null : state.metadata.get(idempotencyKey);
  }

  boolean isBound() {
    return current.get() != null;
  }

  Scope bind(Map<String, EntryMetadata> metadata) {
    return bind(metadata, null);
  }

  Scope bind(Map<String, EntryMetadata> metadata, WalletReservation reservation) {
    if (current.get() != null) {
      throw new IllegalStateException("A ledger posting context is already active");
    }
    current.set(new State(Collections.unmodifiableMap(new HashMap<>(metadata)), reservation));
    return new Scope();
  }

  boolean consumeReservation(
      String idempotencyKey, UUID walletId, String currency, BigDecimal amount) {
    State state = current.get();
    if (state == null || state.reservation == null) return false;
    WalletReservation reservation = state.reservation;
    if (!reservation.idempotencyKey().equals(idempotencyKey)) return false;
    if (state.reservationConsumed
        || !reservation.walletId().equals(walletId)
        || !reservation.currency().equals(currency)
        || reservation.amount().compareTo(amount) != 0) {
      throw new IllegalStateException("Reserved debit does not match the ledger posting");
    }
    state.reservationConsumed = true;
    return true;
  }

  void requireReservationConsumed() {
    State state = current.get();
    if (state != null && state.reservation != null && !state.reservationConsumed) {
      throw new IllegalStateException("Journal did not consume its wallet reservation");
    }
  }

  void record(PostingResult result) {
    State state = current.get();
    if (state == null) {
      return;
    }
    if (state.result == null) {
      state.result = result;
    } else if (state.result != result) {
      throw new IllegalStateException("A journal cannot mix replayed and newly posted entries");
    }
  }

  enum PostingResult {
    NEW,
    REPLAY
  }

  public record EntryMetadata(
      String journalReference, String narration, java.math.BigDecimal rate, String quoteId) {
    public EntryMetadata(String journalReference, String narration) {
      this(journalReference, narration, null, null);
    }
  }

  private static final class State {
    private final Map<String, EntryMetadata> metadata;
    private final WalletReservation reservation;
    private boolean reservationConsumed;
    private PostingResult result;

    private State(Map<String, EntryMetadata> metadata, WalletReservation reservation) {
      this.metadata = metadata;
      this.reservation = reservation;
    }
  }

  final class Scope implements AutoCloseable {
    private boolean closed;

    @Override
    public void close() {
      if (!closed) {
        current.remove();
        closed = true;
      }
    }
  }
}
