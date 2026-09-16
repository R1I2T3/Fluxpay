package com.fluxpay.m5.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.m5.domain.M5PolicyIndexStore;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class M5PolicyDeletionServiceTest {

  @Test
  void removesVectorStateBeforeRemovingThePolicyDocument() {
    UUID policyId = UUID.randomUUID();
    PolicyDocumentRepository documents = mock(PolicyDocumentRepository.class);
    M5PolicyIndexStore indexStore = mock(M5PolicyIndexStore.class);
    PolicyDocument document = mock(PolicyDocument.class);
    when(documents.findById(policyId)).thenReturn(Optional.of(document));

    new M5PolicyDeletionService(documents, indexStore).delete(policyId);

    InOrder deletion = inOrder(indexStore, documents);
    deletion.verify(indexStore).delete(policyId);
    deletion.verify(documents).delete(document);
  }

  @Test
  void refusesToDeleteAMissingPolicy() {
    UUID policyId = UUID.randomUUID();
    PolicyDocumentRepository documents = mock(PolicyDocumentRepository.class);
    when(documents.findById(policyId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> new M5PolicyDeletionService(documents, mock(M5PolicyIndexStore.class)).delete(policyId))
        .isInstanceOf(java.util.NoSuchElementException.class);
  }
}
