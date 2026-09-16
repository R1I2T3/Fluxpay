package com.fluxpay.m5.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.m5.api.CopilotRequest;
import com.fluxpay.m5.domain.M5EmbeddingPort;
import com.fluxpay.m5.domain.PolicyMatch;
import com.fluxpay.m5.domain.PolicySearchPort;
import com.fluxpay.m5.infrastructure.config.M5VectorProperties;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M5CopilotServiceTest {

  @Test
  void returnsACitedExtractiveAnswerFromTheActivePolicyGeneration() {
    M5EmbeddingPort embeddings = mock(M5EmbeddingPort.class);
    PolicySearchPort search = mock(PolicySearchPort.class);
    float[] query = new float[] {0.1f, 0.2f};
    UUID documentId = UUID.fromString("e363b7a8-6830-48d4-889a-a8543f6285b5");
    when(embeddings.embedQuery("When is KYC required?")).thenReturn(query);
    when(search.search(any(float[].class), eq("ollama/qwen3-embedding:4b/1536"), eq(5)))
        .thenReturn(
            List.of(
                new PolicyMatch(
                    UUID.randomUUID(),
                    documentId,
                    "KYC policy",
                    2,
                    "Verify the customer before releasing a payment.",
                    0.08)));
    M5CopilotService service =
        new M5CopilotService(
            embeddings,
            search,
            new M5VectorProperties(
                "http://localhost:11434",
                "qwen3-embedding:4b",
                1536,
                "ollama/qwen3-embedding:4b/1536",
                "m5-sentence-v1"));

    var answer = service.ask(new CopilotRequest("When is KYC required?", null));

    assertThat(answer.answer())
        .isEqualTo(
            "Based on the \"KYC policy\" policy: Verify the customer before releasing a payment.");
    assertThat(answer.sources())
        .singleElement()
        .satisfies(
            source -> {
              assertThat(source.policyDocumentId()).isEqualTo(documentId);
              assertThat(source.title()).isEqualTo("KYC policy");
              assertThat(source.chunkNumber()).isEqualTo(2);
            });
    verify(search).search(query, "ollama/qwen3-embedding:4b/1536", 5);
  }

  @Test
  void saysWhenNoIndexedPolicyCanAnswerTheQuestion() {
    M5EmbeddingPort embeddings = mock(M5EmbeddingPort.class);
    PolicySearchPort search = mock(PolicySearchPort.class);
    when(embeddings.embedQuery("What is the holiday policy?")).thenReturn(new float[] {0.1f});
    when(search.search(any(float[].class), any(String.class), any(Integer.class))).thenReturn(List.of());
    M5CopilotService service =
        new M5CopilotService(
            embeddings,
            search,
            new M5VectorProperties(
                "http://localhost:11434",
                "qwen3-embedding:4b",
                1536,
                "ollama/qwen3-embedding:4b/1536",
                "m5-sentence-v1"));

    var answer = service.ask(new CopilotRequest("What is the holiday policy?", null));

    assertThat(answer.answer()).contains("couldn't find an active indexed policy");
    assertThat(answer.sources()).isEmpty();
  }
}
