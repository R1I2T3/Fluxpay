package com.fluxpay.service;

import com.fluxpay.beans.PolicyChunk;
import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.dto.IndexResponse;
import com.fluxpay.repository.PolicyChunkRepository;
import com.fluxpay.repository.PolicyDocumentRepository;
import com.fluxpay.repository.PolicyVectorRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements the indexing flow from product spec section 10.6: split a policy document's content
 * into ~400-700 word chunks, embed each one, store the embedding in {@code policy_chunks}.
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Service
public class PolicyIndexingService {

  private final PolicyDocumentRepository documentRepository;
  private final PolicyChunkRepository chunkRepository;
  private final PolicyVectorRepository vectorRepository;
  private final EmbeddingProvider embeddingProvider;

  public PolicyIndexingService(
      PolicyDocumentRepository documentRepository,
      PolicyChunkRepository chunkRepository,
      PolicyVectorRepository vectorRepository,
      EmbeddingProvider embeddingProvider) {
    this.documentRepository = documentRepository;
    this.chunkRepository = chunkRepository;
    this.vectorRepository = vectorRepository;
    this.embeddingProvider = embeddingProvider;
  }

  /**
   * Rebuilds every chunk + embedding for a policy document from its current {@code content}.
   * Safe to call again later (e.g. after the content changes) -- existing chunks are replaced,
   * not appended to.
   */
  @Transactional
  public IndexResponse reindex(UUID policyDocumentId) {
    PolicyDocument document =
        documentRepository
            .findById(policyDocumentId)
            .orElseThrow(
                () -> new NoSuchElementException("Policy document not found: " + policyDocumentId));

    List<PolicyChunk> existing =
        chunkRepository.findByPolicyDocumentIdOrderByChunkNumberAsc(policyDocumentId);
    chunkRepository.deleteAll(existing);
    chunkRepository.flush();

    List<String> pieces = TextChunker.chunk(document.getContent());
    int chunkNumber = 1;
    for (String piece : pieces) {
      PolicyChunk chunk = new PolicyChunk();
      chunk.setPolicyDocument(document);
      chunk.setChunkNumber(chunkNumber++);
      chunk.setContent(piece);
      PolicyChunk saved = chunkRepository.saveAndFlush(chunk);
      vectorRepository.saveEmbedding(saved.getId(), embeddingProvider.embedDocument(piece));
    }

    return new IndexResponse(
        policyDocumentId, pieces.size(), embeddingProvider.dimensions(), providerName());
  }

  private String providerName() {
    return embeddingProvider.getClass().getSimpleName();
  }
}
