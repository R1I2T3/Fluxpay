package com.fluxpay.repository;

import com.fluxpay.beans.PolicyChunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyChunkRepository extends JpaRepository<PolicyChunk, UUID> {

  List<PolicyChunk> findByPolicyDocumentIdOrderByChunkNumberAsc(UUID policyDocumentId);

  long countByPolicyDocumentId(UUID policyDocumentId);
}
