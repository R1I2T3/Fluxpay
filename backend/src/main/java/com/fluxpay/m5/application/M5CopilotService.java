package com.fluxpay.m5.application;

import com.fluxpay.m5.api.CopilotAnswerResponse;
import com.fluxpay.m5.api.CopilotRequest;
import com.fluxpay.m5.api.CopilotSource;
import com.fluxpay.m5.domain.M5EmbeddingPort;
import com.fluxpay.m5.domain.PolicyMatch;
import com.fluxpay.m5.domain.PolicySearchPort;
import com.fluxpay.m5.infrastructure.config.M5VectorProperties;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds cited, extractive compliance answers from the current indexed policy corpus. */
@Service
public class M5CopilotService {
  private static final int TOP_K = 5;

  private final M5EmbeddingPort embeddingPort;
  private final PolicySearchPort policySearchPort;
  private final M5VectorProperties vectorProperties;

  public M5CopilotService(
      M5EmbeddingPort embeddingPort,
      PolicySearchPort policySearchPort,
      M5VectorProperties vectorProperties) {
    this.embeddingPort = embeddingPort;
    this.policySearchPort = policySearchPort;
    this.vectorProperties = vectorProperties;
  }

  @Transactional(readOnly = true)
  public CopilotAnswerResponse ask(CopilotRequest request) {
    float[] queryEmbedding = embeddingPort.embedQuery(request.question());
    List<PolicyMatch> matches =
        policySearchPort.search(queryEmbedding, vectorProperties.embeddingSpaceId(), TOP_K);
    if (matches.isEmpty()) {
      return new CopilotAnswerResponse(
          "I couldn't find an active indexed policy that answers this question yet. "
              + "Upload and index a relevant policy, then try again.",
          List.of());
    }
    List<CopilotSource> sources =
        matches.stream()
            .map(
                match ->
                    new CopilotSource(
                        match.policyDocumentId(),
                        match.title(),
                        match.chunkNumber(),
                        excerpt(match.content(), 200)))
            .toList();
    PolicyMatch bestMatch = matches.get(0);
    String answer =
        "Based on the \""
            + bestMatch.title()
            + "\" policy: "
            + excerpt(bestMatch.content(), 320);
    return new CopilotAnswerResponse(answer, sources);
  }

  private static String excerpt(String content, int maxLength) {
    String trimmed = content.trim();
    return trimmed.length() <= maxLength
        ? trimmed
        : trimmed.substring(0, maxLength).trim() + "...";
  }
}
