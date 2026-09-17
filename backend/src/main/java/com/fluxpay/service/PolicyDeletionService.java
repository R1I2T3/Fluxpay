package com.fluxpay.service;

import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.common.contracts.PolicyIndexStore;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Removes a policy document together with every vector generation and embedded chunk it owns. */
@Service
public class PolicyDeletionService {
  private final PolicyDocumentRepository documents;
  private final PolicyIndexStore indexStore;

  public PolicyDeletionService(PolicyDocumentRepository documents, PolicyIndexStore indexStore) {
    this.documents = documents;
    this.indexStore = indexStore;
  }

  @Transactional
  public void delete(UUID policyDocumentId) {
    PolicyDocument document =
        documents
            .findById(policyDocumentId)
            .orElseThrow(
                () -> new NoSuchElementException("Policy document not found: " + policyDocumentId));
    indexStore.delete(policyDocumentId);
    documents.delete(document);
  }
}
