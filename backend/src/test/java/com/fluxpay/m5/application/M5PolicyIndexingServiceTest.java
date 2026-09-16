package com.fluxpay.m5.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.m5.domain.IndexedPolicyChunk;
import com.fluxpay.m5.domain.M5EmbeddingPort;
import com.fluxpay.m5.domain.M5PolicyIndexStore;
import com.fluxpay.m5.infrastructure.config.M5VectorProperties;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class M5PolicyIndexingServiceTest {

  @Test
  void publishesEmbeddedChunksAsANewActivePolicyGeneration() {
    UUID documentId = UUID.fromString("a157fc3a-286b-4bb9-a355-d6a4eb8f85f0");
    PolicyDocumentRepository documents = mock(PolicyDocumentRepository.class);
    PolicyDocument document = mock(PolicyDocument.class);
    M5EmbeddingPort embeddings = mock(M5EmbeddingPort.class);
    M5PolicyIndexStore store = mock(M5PolicyIndexStore.class);
    String content = "Verify the customer before releasing a payment.";
    float[] vector = new float[1536];
    vector[0] = 1.0f;
    when(documents.findById(documentId)).thenReturn(Optional.of(document));
    when(document.getContent()).thenReturn(content);
    when(embeddings.embedDocument(content)).thenReturn(vector);
    M5PolicyIndexingService service =
        new M5PolicyIndexingService(
            documents,
            new M5PolicyChunker(),
            embeddings,
            store,
            new M5VectorProperties(
                "http://localhost:11434",
                "qwen3-embedding:4b",
                1536,
                "ollama/qwen3-embedding:4b/1536",
                "m5-sentence-v1"));

    var result = service.index(documentId);

    assertThat(result.policyDocumentId()).isEqualTo(documentId);
    assertThat(result.chunkCount()).isEqualTo(1);
    ArgumentCaptor<List<IndexedPolicyChunk>> chunks = ArgumentCaptor.forClass(List.class);
    verify(store)
        .publish(
            eq(documentId),
            eq("ollama/qwen3-embedding:4b/1536"),
            eq("m5-sentence-v1"),
            chunks.capture());
    assertThat(chunks.getValue())
        .singleElement()
        .satisfies(
            chunk -> {
              assertThat(chunk.chunkNumber()).isEqualTo(1);
              assertThat(chunk.content()).isEqualTo(content);
              assertThat(chunk.embedding()).containsExactly(vector);
            });
  }
}
