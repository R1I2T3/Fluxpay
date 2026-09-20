package com.fluxpay.service;

import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.common.contracts.EmbeddingPort;
import com.fluxpay.common.contracts.PolicyIndexStore;
import com.fluxpay.config.VectorProperties;
import com.fluxpay.dto.IndexedPolicyChunk;
import com.fluxpay.dto.PolicyIndexResult;
import com.fluxpay.repository.PolicyDocumentRepository;
import com.fluxpay.repository.PolicyGuidanceRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rebuilds a policy's chunks and publishes a new active Qwen vector generation. */
@Service
public class PolicyIndexingService {
  private final PolicyDocumentRepository policyDocuments;
  private final PolicyChunker chunker;
  private final EmbeddingPort embeddings;
  private final PolicyIndexStore indexStore;
  private final VectorProperties vectorProperties;
  private final PolicyGuidanceRepository guidance;

  public PolicyIndexingService(
      PolicyDocumentRepository policyDocuments,
      PolicyChunker chunker,
      EmbeddingPort embeddings,
      PolicyIndexStore indexStore,
      VectorProperties vectorProperties) {
    this(policyDocuments, chunker, embeddings, indexStore, vectorProperties, null);
  }

  @Autowired
  public PolicyIndexingService(
      PolicyDocumentRepository policyDocuments,
      PolicyChunker chunker,
      EmbeddingPort embeddings,
      PolicyIndexStore indexStore,
      VectorProperties vectorProperties,
      PolicyGuidanceRepository guidance) {
    this.policyDocuments = policyDocuments;
    this.chunker = chunker;
    this.embeddings = embeddings;
    this.indexStore = indexStore;
    this.vectorProperties = vectorProperties;
    this.guidance = guidance;
  }

  @Transactional
  public PolicyIndexResult index(UUID policyDocumentId) {
    PolicyDocument document =
        policyDocuments
            .findById(policyDocumentId)
            .orElseThrow(
                () -> new NoSuchElementException("Policy document not found: " + policyDocumentId));
    List<String> contentChunks = new java.util.ArrayList<>(chunker.chunk(document.getContent()));
    if (guidance != null) {
      guidance
          .findByPolicyDocumentIdOrderByCreatedAtDesc(policyDocumentId)
          .forEach(item -> contentChunks.add("Policy guidance / precedent:\n" + item.getContent()));
    }
    if (contentChunks.size() > 16) {
      throw new IllegalStateException(
          "Policy and its guidance produce more than 16 searchable chunks");
    }
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
