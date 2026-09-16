package com.fluxpay.m5.application;

import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.m5.domain.IndexedPolicyChunk;
import com.fluxpay.m5.domain.M5EmbeddingPort;
import com.fluxpay.m5.domain.M5PolicyIndexStore;
import com.fluxpay.m5.infrastructure.config.M5VectorProperties;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds a policy's chunks and publishes a new active Qwen vector generation. */
@Service
public class M5PolicyIndexingService {
  private final PolicyDocumentRepository policyDocuments;
  private final M5PolicyChunker chunker;
  private final M5EmbeddingPort embeddings;
  private final M5PolicyIndexStore indexStore;
  private final M5VectorProperties vectorProperties;

  public M5PolicyIndexingService(
      PolicyDocumentRepository policyDocuments,
      M5PolicyChunker chunker,
      M5EmbeddingPort embeddings,
      M5PolicyIndexStore indexStore,
      M5VectorProperties vectorProperties) {
    this.policyDocuments = policyDocuments;
    this.chunker = chunker;
    this.embeddings = embeddings;
    this.indexStore = indexStore;
    this.vectorProperties = vectorProperties;
  }

  @Transactional
  public PolicyIndexResult index(UUID policyDocumentId) {
    PolicyDocument document =
        policyDocuments
            .findById(policyDocumentId)
            .orElseThrow(
                () -> new NoSuchElementException("Policy document not found: " + policyDocumentId));
    List<String> contentChunks = chunker.chunk(document.getContent());
    List<IndexedPolicyChunk> chunks =
        java.util.stream.IntStream.range(0, contentChunks.size())
            .mapToObj(
                index ->
                    new IndexedPolicyChunk(
                        index + 1,
                        contentChunks.get(index),
                        validateEmbedding(embeddings.embedDocument(contentChunks.get(index)))))
            .toList();
    indexStore.publish(
        policyDocumentId,
        vectorProperties.embeddingSpaceId(),
        vectorProperties.chunkerVersion(),
        chunks);
    return new PolicyIndexResult(policyDocumentId, chunks.size());
  }

  private float[] validateEmbedding(float[] embedding) {
    if (embedding == null || embedding.length != vectorProperties.dimensions()) {
      throw new IllegalStateException(
          "Policy embedding must contain exactly " + vectorProperties.dimensions() + " values");
    }
    for (float value : embedding) {
      if (!Float.isFinite(value)) {
        throw new IllegalStateException("Policy embedding contains a nonfinite value");
      }
    }
    return embedding;
  }
}
