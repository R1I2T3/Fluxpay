package com.fluxpay.service;

import com.fluxpay.beans.PolicyChunk;
import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.common.enums.PolicyChunkSource;
import com.fluxpay.dto.PolicyChunkRequest;
import com.fluxpay.dto.PolicyChunkResponse;
import com.fluxpay.repository.PolicyChunkRepository;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyChunkService {

  private final PolicyChunkRepository chunkRepository;
  private final PolicyDocumentRepository documentRepository;

  public PolicyChunkService(
      PolicyChunkRepository chunkRepository, PolicyDocumentRepository documentRepository) {
    this.chunkRepository = chunkRepository;
    this.documentRepository = documentRepository;
  }

  @Transactional
  public PolicyChunkResponse addChunk(UUID policyDocumentId, PolicyChunkRequest request) {
    PolicyDocument document =
        documentRepository
            .findById(policyDocumentId)
            .orElseThrow(
                () -> new NoSuchElementException("Policy document not found: " + policyDocumentId));
    long nextNumber = chunkRepository.countByPolicyDocumentId(policyDocumentId) + 1;
    PolicyChunk chunk = new PolicyChunk();
    chunk.setPolicyDocument(document);
    chunk.setChunkNumber((int) nextNumber);
    chunk.setContent(request.content());
    chunk.setSource(PolicyChunkSource.MANUAL);
    PolicyChunk saved = chunkRepository.save(chunk);
    return response(saved, policyDocumentId);
  }

  @Transactional
  public PolicyChunkResponse updateChunk(
      UUID policyDocumentId, UUID chunkId, PolicyChunkRequest request) {
    PolicyChunk chunk = editableChunk(policyDocumentId, chunkId);
    chunk.setContent(request.content());
    return response(chunkRepository.save(chunk), policyDocumentId);
  }

  @Transactional
  public void deleteChunk(UUID policyDocumentId, UUID chunkId) {
    chunkRepository.delete(findChunk(policyDocumentId, chunkId));
  }

  @Transactional(readOnly = true)
  public List<PolicyChunkResponse> list(UUID policyDocumentId) {
    return chunkRepository.findByPolicyDocumentIdOrderByChunkNumberAsc(policyDocumentId).stream()
        .map(c -> response(c, policyDocumentId))
        .toList();
  }

  private PolicyChunk editableChunk(UUID policyDocumentId, UUID chunkId) {
    PolicyChunk chunk = findChunk(policyDocumentId, chunkId);
    if (chunk.getSource() != PolicyChunkSource.MANUAL) {
      throw new IllegalStateException("Only manually added chunks can be edited");
    }
    return chunk;
  }

  private PolicyChunk findChunk(UUID policyDocumentId, UUID chunkId) {
    PolicyChunk chunk =
        chunkRepository
            .findById(chunkId)
            .orElseThrow(() -> new NoSuchElementException("Policy chunk not found: " + chunkId));
    if (!chunk.getPolicyDocument().getId().equals(policyDocumentId)) {
      throw new NoSuchElementException("Policy chunk not found: " + chunkId);
    }
    return chunk;
  }

  private static PolicyChunkResponse response(PolicyChunk chunk, UUID policyDocumentId) {
    return new PolicyChunkResponse(
        chunk.getId(),
        policyDocumentId,
        chunk.getChunkNumber(),
        chunk.getContent(),
        chunk.getSource() == PolicyChunkSource.MANUAL,
        chunk.getCreatedAt());
  }
}
