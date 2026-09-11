package com.fluxpay.service;

import com.fluxpay.beans.PolicyChunk;
import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.dto.PolicyChunkRequest;
import com.fluxpay.dto.PolicyChunkResponse;
import com.fluxpay.repository.PolicyChunkRepository;
import com.fluxpay.repository.PolicyDocumentRepository;
import com.fluxpay.repository.PolicyVectorRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.context.annotation.Profile("m5-legacy")
@Service
public class PolicyChunkService {

  private final PolicyChunkRepository chunkRepository;
  private final PolicyDocumentRepository documentRepository;
  private final PolicyVectorRepository vectorRepository;
  private final EmbeddingProvider embeddingProvider;

  public PolicyChunkService(
      PolicyChunkRepository chunkRepository,
      PolicyDocumentRepository documentRepository,
      PolicyVectorRepository vectorRepository,
      EmbeddingProvider embeddingProvider) {
    this.chunkRepository = chunkRepository;
    this.documentRepository = documentRepository;
    this.vectorRepository = vectorRepository;
    this.embeddingProvider = embeddingProvider;
  }

  /** Manually appends one chunk and embeds it immediately, so it's searchable right away. */
  @Transactional
  public PolicyChunkResponse addChunk(UUID policyDocumentId, PolicyChunkRequest request) {
    PolicyDocument document =
        documentRepository
            .findById(policyDocumentId)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "Policy document not found: " + policyDocumentId));
    long nextNumber = chunkRepository.countByPolicyDocumentId(policyDocumentId) + 1;
    PolicyChunk chunk = new PolicyChunk();
    chunk.setPolicyDocument(document);
    chunk.setChunkNumber((int) nextNumber);
    chunk.setContent(request.content());
    PolicyChunk saved = chunkRepository.saveAndFlush(chunk);
    vectorRepository.saveEmbedding(saved.getId(), embeddingProvider.embed(request.content()));
    return new PolicyChunkResponse(
        saved.getId(),
        policyDocumentId,
        saved.getChunkNumber(),
        saved.getContent(),
        saved.getCreatedAt());
  }

  @Transactional(readOnly = true)
  public List<PolicyChunkResponse> list(UUID policyDocumentId) {
    return chunkRepository.findByPolicyDocumentIdOrderByChunkNumberAsc(policyDocumentId).stream()
        .map(
            c ->
                new PolicyChunkResponse(
                    c.getId(),
                    policyDocumentId,
                    c.getChunkNumber(),
                    c.getContent(),
                    c.getCreatedAt()))
        .toList();
  }
}
