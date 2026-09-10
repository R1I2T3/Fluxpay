package com.fluxpay.repository;

import com.fluxpay.beans.ComplianceCase;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplianceCaseRepository extends JpaRepository<ComplianceCase, UUID> {

  List<ComplianceCase> findByStatusOrderByCreatedAtDesc(ComplianceCaseStatus status);

  List<ComplianceCase> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

  List<ComplianceCase> findAllByOrderByCreatedAtDesc();
}
