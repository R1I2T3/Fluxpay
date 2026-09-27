package com.fluxpay.service;

import com.fluxpay.repository.PolicyDocumentRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Rebuilds active policy search generations before the HTTP server is started. */
@Component
public class PolicyStartupIndexer {
  private final PolicyDocumentRepository documents;
  private final PolicyIndexingService indexing;

  public PolicyStartupIndexer(
      PolicyDocumentRepository documents, PolicyIndexingService indexing) {
    this.documents = documents;
    this.indexing = indexing;
  }

  @PostConstruct
  void indexPoliciesBeforeServerStarts() {
    documents.findAllByOrderByCreatedAtDesc().forEach(document -> indexing.index(document.getId()));
  }
}
