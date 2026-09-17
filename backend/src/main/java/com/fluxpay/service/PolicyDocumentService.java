package com.fluxpay.service;

import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.dto.PolicyChunkResponse;
import com.fluxpay.dto.PolicyDocumentRequest;
import com.fluxpay.dto.PolicyDocumentResponse;
import com.fluxpay.repository.PolicyChunkRepository;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyDocumentService {

  private final PolicyDocumentRepository repository;
  private final PolicyChunkRepository chunkRepository;

  public PolicyDocumentService(
      PolicyDocumentRepository repository, PolicyChunkRepository chunkRepository) {
    this.repository = repository;
    this.chunkRepository = chunkRepository;
  }

  @Transactional
  public PolicyDocumentResponse create(PolicyDocumentRequest request) {
    String hash = sha256(request.title() + "|" + request.content());
    repository
        .findByDocumentHash(hash)
        .ifPresent(
            existing -> {
              throw new IllegalStateException(
                  "Duplicate policy content, existing id: " + existing.getId());
            });
    PolicyDocument entity = new PolicyDocument();
    entity.setTitle(request.title());
    entity.setCategory(request.category());
    entity.setContent(request.content());
    entity.setDocumentHash(hash);
    return toResponse(repository.save(entity), List.of());
  }

  @Transactional(readOnly = true)
  public List<PolicyDocumentResponse> list() {
    return repository.findAllByOrderByCreatedAtDesc().stream()
        .map(doc -> toResponse(doc, chunksOf(doc.getId())))
        .toList();
  }

  @Transactional(readOnly = true)
  public PolicyDocumentResponse getById(UUID id) {
    PolicyDocument doc = find(id);
    return toResponse(doc, chunksOf(id));
  }

  private List<PolicyChunkResponse> chunksOf(UUID documentId) {
    return chunkRepository.findByPolicyDocumentIdOrderByChunkNumberAsc(documentId).stream()
        .map(
            c ->
                new PolicyChunkResponse(
                    c.getId(), documentId, c.getChunkNumber(), c.getContent(), c.getCreatedAt()))
        .toList();
  }

  private PolicyDocument find(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new NoSuchElementException("Policy document not found: " + id));
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to hash policy content", e);
    }
  }

  private PolicyDocumentResponse toResponse(PolicyDocument e, List<PolicyChunkResponse> chunks) {
    return new PolicyDocumentResponse(
        e.getId(),
        e.getTitle(),
        e.getCategory(),
        e.getContent(),
        e.getDocumentHash(),
        e.getCreatedAt(),
        chunks);
  }
}
