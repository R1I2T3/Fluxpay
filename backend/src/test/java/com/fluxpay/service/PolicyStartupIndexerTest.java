package com.fluxpay.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.common.contracts.EmbeddingModelLifecycle;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PolicyStartupIndexerTest {

  @Test
  void rebuildsEveryPolicyIndexAtStartup() throws Exception {
    PolicyDocumentRepository documents = mock(PolicyDocumentRepository.class);
    PolicyIndexingService indexing = mock(PolicyIndexingService.class);
    EmbeddingModelLifecycle embeddingModel = mock(EmbeddingModelLifecycle.class);
    PolicyDocument first = mock(PolicyDocument.class);
    PolicyDocument second = mock(PolicyDocument.class);
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    when(first.getId()).thenReturn(firstId);
    when(second.getId()).thenReturn(secondId);
    when(documents.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(first, second));

    new PolicyStartupIndexer(documents, indexing, embeddingModel).indexPoliciesBeforeServerStarts();

    verify(indexing).index(firstId);
    verify(indexing).index(secondId);
    verify(embeddingModel).unload();
  }

  @Test
  void failsStartupWhenAnyPolicyCannotBeIndexed() {
    PolicyDocumentRepository documents = mock(PolicyDocumentRepository.class);
    PolicyIndexingService indexing = mock(PolicyIndexingService.class);
    EmbeddingModelLifecycle embeddingModel = mock(EmbeddingModelLifecycle.class);
    PolicyDocument document = mock(PolicyDocument.class);
    UUID documentId = UUID.randomUUID();
    when(document.getId()).thenReturn(documentId);
    when(documents.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(document));
    doThrow(new IllegalStateException("Ollama unavailable")).when(indexing).index(documentId);

    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> new PolicyStartupIndexer(documents, indexing, embeddingModel).indexPoliciesBeforeServerStarts());
  }
}
