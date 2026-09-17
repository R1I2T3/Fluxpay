package com.fluxpay.service;

import com.fluxpay.beans.PolicyChunk;
import com.fluxpay.beans.PolicyDocument;
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
                () ->
                    new NoSuchElementException(
                        "Policy document not found: " + policyDocumentId));
    long nextNumber = chunkRepository.countByPolicyDocumentId(policyDocumentId) + 1;
    PolicyChunk chunk = new PolicyChunk();
    chunk.setPolicyDocument(document);
    chunk.setChunkNumber((int) nextNumber);
    chunk.setContent(request.content());
    PolicyChunk saved = chunkRepository.save(chunk);
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
