package com.fluxpay.repository;

import com.fluxpay.beans.KycDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KycDocumentRepository extends JpaRepository<KycDocument, UUID> {
  List<KycDocument> findAllByKycCaseIdOrderByUploadedAtAsc(UUID kycCaseId);
}
