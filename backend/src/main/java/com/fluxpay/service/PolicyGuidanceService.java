package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import com.fluxpay.dto.*;
import com.fluxpay.repository.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PolicyGuidanceService {
  private final PolicyGuidanceRepository guidance;
  private final PolicyDocumentRepository policies;
  private final ComplianceCaseRepository cases;

  public PolicyGuidanceService(
      PolicyGuidanceRepository guidance,
      PolicyDocumentRepository policies,
      ComplianceCaseRepository cases) {
    this.guidance = guidance;
    this.policies = policies;
    this.cases = cases;
  }

  @Transactional(readOnly = true)
  public List<PolicyGuidanceResponse> list(UUID policyId) {
    return guidance.findByPolicyDocumentIdOrderByCreatedAtDesc(policyId).stream()
        .map(this::response)
        .toList();
  }

  @Transactional
  public PolicyGuidanceResponse add(UUID policyId, PolicyGuidanceRequest request) {
    PolicyDocument policy =
        policies
            .findById(policyId)
            .orElseThrow(
                () -> new NoSuchElementException("Policy document not found: " + policyId));
    ComplianceCase complianceCase =
        cases
            .findById(request.complianceCaseId())
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "Compliance case not found: " + request.complianceCaseId()));
    if (complianceCase.getStatus() == ComplianceCaseStatus.OPEN)
      throw new IllegalStateException(
          "Only completed compliance cases can be added as policy guidance");
    PolicyGuidance entity = new PolicyGuidance();
    entity.setPolicyDocument(policy);
    entity.setComplianceCase(complianceCase);
    entity.setContent(request.content().trim());
    return response(guidance.save(entity));
  }

  @Transactional
  public PolicyGuidanceResponse update(
      UUID policyId, UUID guidanceId, PolicyGuidanceUpdateRequest request) {
    PolicyGuidance entity = find(policyId, guidanceId);
    entity.setContent(request.content().trim());
    return response(guidance.save(entity));
  }

  @Transactional
  public void delete(UUID policyId, UUID guidanceId) {
    guidance.delete(find(policyId, guidanceId));
  }

  private PolicyGuidance find(UUID policyId, UUID id) {
    PolicyGuidance entity =
        guidance
            .findById(id)
            .orElseThrow(() -> new NoSuchElementException("Policy guidance not found: " + id));
    if (!entity.getPolicyDocument().getId().equals(policyId))
      throw new NoSuchElementException("Policy guidance not found: " + id);
    return entity;
  }

  private PolicyGuidanceResponse response(PolicyGuidance e) {
    return new PolicyGuidanceResponse(
        e.getId(),
        e.getPolicyDocument().getId(),
        e.getComplianceCase().getId(),
        e.getContent(),
        e.getCreatedAt(),
        e.getUpdatedAt());
  }
}
