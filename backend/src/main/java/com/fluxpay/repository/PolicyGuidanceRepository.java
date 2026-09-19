package com.fluxpay.repository;
import com.fluxpay.beans.PolicyGuidance;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PolicyGuidanceRepository extends JpaRepository<PolicyGuidance, UUID> {
  List<PolicyGuidance> findByPolicyDocumentIdOrderByCreatedAtDesc(UUID policyDocumentId);
}
