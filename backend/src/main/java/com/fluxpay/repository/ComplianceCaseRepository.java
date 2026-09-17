package com.fluxpay.repository;

import com.fluxpay.beans.ComplianceCase;
import com.fluxpay.common.enums.ComplianceCaseStatus;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComplianceCaseRepository extends JpaRepository<ComplianceCase, UUID> {

  List<ComplianceCase> findByStatusOrderByCreatedAtDesc(ComplianceCaseStatus status);

  List<ComplianceCase> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId);

  java.util.Optional<ComplianceCase> findByReviewReference(String reviewReference);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from ComplianceCase c where c.id = :id")
  java.util.Optional<ComplianceCase> lockById(@Param("id") UUID id);

  List<ComplianceCase> findAllByOrderByCreatedAtDesc();
}
