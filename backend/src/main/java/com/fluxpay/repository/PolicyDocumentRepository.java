package com.fluxpay.repository;

import com.fluxpay.beans.PolicyDocument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, UUID> {

  Optional<PolicyDocument> findByDocumentHash(String documentHash);

  List<PolicyDocument> findAllByOrderByCreatedAtDesc();
}
