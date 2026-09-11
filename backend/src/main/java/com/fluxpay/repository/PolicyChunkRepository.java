package com.fluxpay.repository;

import com.fluxpay.beans.PolicyChunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

@org.springframework.context.annotation.Profile("m5-legacy")
public interface PolicyChunkRepository extends JpaRepository<PolicyChunk, UUID> {

  List<PolicyChunk> findByPolicyDocumentIdOrderByChunkNumberAsc(UUID policyDocumentId);

  long countByPolicyDocumentId(UUID policyDocumentId);
}
