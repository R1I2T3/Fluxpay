package com.fluxpay.service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
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
    if (current.get() != null) {
      throw new IllegalStateException("A ledger posting context is already active");
    }
    current.set(new State(Collections.unmodifiableMap(new HashMap<>(metadata))));
    return new Scope();
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

  public record EntryMetadata(String journalReference, String narration) {}

  private static final class State {
    private final Map<String, EntryMetadata> metadata;
    private PostingResult result;

    private State(Map<String, EntryMetadata> metadata) {
      this.metadata = metadata;
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
