package com.fluxpay.common.contracts;

import com.fluxpay.dto.IndexedPolicyChunk;
import java.util.List;
import java.util.UUID;

/**
 * Publishes a fully embedded policy generation atomically and makes it the document's active one.
 */
public interface PolicyIndexStore {
  void publish(
      UUID policyDocumentId,
      String embeddingSpaceId,
      String chunkerVersion,
      List<IndexedPolicyChunk> chunks);

  /** Removes the active vector generation and all embedded chunks for one policy document. */
  void delete(UUID policyDocumentId);
}
