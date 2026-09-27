package com.fluxpay.service;

import com.fluxpay.common.contracts.EmbeddingModelLifecycle;
import com.fluxpay.repository.PolicyDocumentRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** Rebuilds active policy search generations before the HTTP server is started. */
@Component
public class PolicyStartupIndexer {
  private final PolicyDocumentRepository documents;
  private final PolicyIndexingService indexing;
  private final EmbeddingModelLifecycle embeddingModel;

  public PolicyStartupIndexer(
      PolicyDocumentRepository documents,
      PolicyIndexingService indexing,
      EmbeddingModelLifecycle embeddingModel) {
    this.documents = documents;
    this.indexing = indexing;
    this.embeddingModel = embeddingModel;
  }

  @PostConstruct
  void indexPoliciesBeforeServerStarts() {
    documents.findAllByOrderByCreatedAtDesc().forEach(document -> indexing.index(document.getId()));
    embeddingModel.unload();
  }
}
