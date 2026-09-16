package com.fluxpay.m5.domain;

import java.util.List;
import java.util.UUID;

/** Publishes a fully embedded policy generation atomically and makes it the document's active one. */
public interface M5PolicyIndexStore {
  void publish(
      UUID policyDocumentId,
      String embeddingSpaceId,
      String chunkerVersion,
      List<IndexedPolicyChunk> chunks);

  /** Removes the active vector generation and all embedded chunks for one policy document. */
  void delete(UUID policyDocumentId);
}
