package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.common.contracts.ChatPort;
import com.fluxpay.common.contracts.EmbeddingPort;
import com.fluxpay.common.contracts.PolicySearchPort;
import com.fluxpay.config.CopilotProperties;
import com.fluxpay.config.VectorProperties;
import com.fluxpay.dto.CopilotRequest;
import com.fluxpay.dto.PolicyMatch;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CopilotServiceTest {

  @Test
  void generatesACitedAnswerFromRelevantPolicyEvidence() {
    EmbeddingPort embeddings = mock(EmbeddingPort.class);
    ChatPort chat = mock(ChatPort.class);
    PolicySearchPort search = mock(PolicySearchPort.class);
    float[] query = new float[] {0.1f, 0.2f};
    UUID documentId = UUID.fromString("e363b7a8-6830-48d4-889a-a8543f6285b5");
    when(embeddings.embedQuery("When is KYC required?")).thenReturn(query);
    when(search.search(any(float[].class), eq("ollama/qwen3-embedding:4b/1536"), eq(3)))
        .thenReturn(
            List.of(
                new PolicyMatch(
                    UUID.randomUUID(),
                    documentId,
                    "KYC policy",
                    2,
                    "Verify the customer before releasing a payment.",
                    0.08)));
    when(chat.answer(eq("When is KYC required?"), any()))
        .thenReturn("KYC must be verified before release.");
    CopilotService service =
        new CopilotService(
            embeddings,
            chat,
            search,
            new VectorProperties(
                "http://localhost:11434",
                "qwen3-embedding:4b",
                1536,
                "ollama/qwen3-embedding:4b/1536",
                "m5-sentence-v1"),
            new CopilotProperties("qwen3:4b", 0.2, 0.65, 90, true, "30m", 512, 2048, 3));

    var answer = service.ask(new CopilotRequest("When is KYC required?", null));

    assertThat(answer.answer()).isEqualTo("KYC must be verified before release.");
    assertThat(answer.sources())
        .singleElement()
        .satisfies(
            source -> {
              assertThat(source.policyDocumentId()).isEqualTo(documentId);
              assertThat(source.title()).isEqualTo("KYC policy");
              assertThat(source.chunkNumber()).isEqualTo(2);
            });
    verify(search).search(query, "ollama/qwen3-embedding:4b/1536", 3);
    verify(chat).answer(eq("When is KYC required?"), any());
  }

  @Test
  void saysWhenNoIndexedPolicyCanAnswerTheQuestion() {
    EmbeddingPort embeddings = mock(EmbeddingPort.class);
    ChatPort chat = mock(ChatPort.class);
    PolicySearchPort search = mock(PolicySearchPort.class);
    when(embeddings.embedQuery("What is the holiday policy?")).thenReturn(new float[] {0.1f});
    when(search.search(any(float[].class), any(String.class), any(Integer.class)))
        .thenReturn(List.of());
    CopilotService service =
        new CopilotService(
            embeddings,
            chat,
            search,
            new VectorProperties(
                "http://localhost:11434",
                "qwen3-embedding:4b",
                1536,
                "ollama/qwen3-embedding:4b/1536",
                "m5-sentence-v1"),
            new CopilotProperties("qwen3:4b", 0.2, 0.65, 90, true, "30m", 512, 2048, 3));

    var answer = service.ask(new CopilotRequest("What is the holiday policy?", null));

    assertThat(answer.answer()).contains("I can help with FluxPay compliance");
    assertThat(answer.sources()).isEmpty();
  }
}
