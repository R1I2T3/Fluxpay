package com.fluxpay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.ComplianceCase;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.common.enums.ComplianceRisk;
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

@org.springframework.context.annotation.Profile("m5-legacy")
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

  /**
   * Used by {@link ComplianceAssessmentService}. Idempotent: if a case already exists for this
   * payment, that case is returned as-is rather than creating a duplicate. LOW risk auto-closes
   * immediately (no admin action needed); MEDIUM/HIGH stay OPEN for review.
   */
  @Transactional
  public ComplianceCaseResponse createFromAssessment(
      UUID paymentId, ComplianceRisk risk, List<String> reasons, String suggestedAction) {
    List<ComplianceCase> existing = repository.findByPaymentIdOrderByCreatedAtDesc(paymentId);
    if (!existing.isEmpty()) {
      return toResponse(existing.get(0));
    }
    ComplianceCase entity = new ComplianceCase();
    entity.setPaymentId(paymentId);
    entity.setRisk(risk);
    entity.setRiskReasons(writeJson(reasons));
    entity.setSuggestedAction(suggestedAction);
    if (risk == ComplianceRisk.LOW) {
      entity.setStatus(ComplianceCaseStatus.CLOSED);
      entity.setDecidedBy("SYSTEM_AUTO");
      entity.setDecidedAt(Instant.now());
      entity.setDecisionReason("Automatically cleared -- low risk assessment.");
    } else {
      entity.setStatus(ComplianceCaseStatus.OPEN);
    }
    return toResponse(repository.save(entity));
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
      // IllegalStateException (not IllegalArgumentException) so GlobalExceptionHandler maps it
      // to a clean 409 instead of falling through to an unhandled 500.
      throw new IllegalStateException("Unable to serialize risk reasons", e);
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
