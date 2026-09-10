package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.ComplianceCase;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.dto.ComplianceCaseRequest;
import com.fluxpay.dto.ComplianceCaseResponse;
import com.fluxpay.dto.ComplianceDecisionRequest;
import com.fluxpay.repository.ComplianceCaseRepository;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceCaseService {

  private final ComplianceCaseRepository repository;
  private final ObjectMapper objectMapper;

  public ComplianceCaseService(ComplianceCaseRepository repository, ObjectMapper objectMapper) {
    this.repository = repository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public ComplianceCaseResponse create(ComplianceCaseRequest request) {
    ComplianceCase entity = new ComplianceCase();
    entity.setPaymentId(request.paymentId());
    entity.setRisk(request.risk());
    entity.setStatus(ComplianceCaseStatus.OPEN);
    entity.setRiskReasons(writeJson(request.riskReasons()));
    entity.setSuggestedAction(request.suggestedAction());
    return toResponse(repository.save(entity));
  }

  @Transactional(readOnly = true)
  public List<ComplianceCaseResponse> list(ComplianceCaseStatus status) {
    List<ComplianceCase> rows =
        status == null
            ? repository.findAllByOrderByCreatedAtDesc()
            : repository.findByStatusOrderByCreatedAtDesc(status);
    return rows.stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public ComplianceCaseResponse getById(UUID id) {
    return toResponse(find(id));
  }

  @Transactional
  public ComplianceCaseResponse approve(UUID id, ComplianceDecisionRequest request) {
    return decide(id, ComplianceCaseStatus.APPROVED, request);
  }

  @Transactional
  public ComplianceCaseResponse reject(UUID id, ComplianceDecisionRequest request) {
    return decide(id, ComplianceCaseStatus.REJECTED, request);
  }

  private ComplianceCaseResponse decide(
      UUID id, ComplianceCaseStatus outcome, ComplianceDecisionRequest request) {
    ComplianceCase entity = find(id);
    if (entity.getStatus() != ComplianceCaseStatus.OPEN) {
      throw new IllegalStateException(
          "Case " + entity.getId() + " is already " + entity.getStatus() + ", cannot re-decide");
    }
    entity.setStatus(outcome);
    entity.setDecidedBy(request.decidedBy());
    entity.setDecidedAt(Instant.now());
    entity.setDecisionReason(request.decisionReason());
    return toResponse(repository.save(entity));
  }

  private ComplianceCase find(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new NoSuchElementException("Compliance case not found: " + id));
  }

  private String writeJson(List<String> reasons) {
    try {
      return objectMapper.writeValueAsString(reasons);
    } catch (Exception e) {
      throw new IllegalArgumentException("Unable to serialize risk reasons", e);
    }
  }

  private List<String> readJson(String json) {
    try {
      return objectMapper.readValue(
          json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
    } catch (Exception e) {
      throw new IllegalStateException("Stored risk_reasons is not valid JSON", e);
    }
  }

  private ComplianceCaseResponse toResponse(ComplianceCase e) {
    return new ComplianceCaseResponse(
        e.getId(),
        e.getPaymentId(),
        e.getRisk(),
        e.getStatus(),
        readJson(e.getRiskReasons()),
        e.getSuggestedAction(),
        e.getDecidedBy(),
        e.getDecidedAt(),
        e.getDecisionReason(),
        e.getCreatedAt());
  }
}
