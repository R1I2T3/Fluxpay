package com.fluxpay.m5.application;

import com.fluxpay.m5.api.CopilotAnswerResponse;
import com.fluxpay.m5.api.CopilotRequest;
import com.fluxpay.m5.api.CopilotSource;
import com.fluxpay.m5.domain.M5EmbeddingPort;
import com.fluxpay.m5.domain.M5ChatPort;
import com.fluxpay.m5.domain.PolicyMatch;
import com.fluxpay.m5.domain.PolicySearchPort;
import com.fluxpay.m5.infrastructure.config.M5VectorProperties;
import com.fluxpay.m5.infrastructure.config.M5CopilotProperties;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds cited, extractive compliance answers from the current indexed policy corpus. */
@Service
public class M5CopilotService {
  private static final int TOP_K = 5;

  private final M5EmbeddingPort embeddingPort;
  private final M5ChatPort chatPort;
  private final PolicySearchPort policySearchPort;
  private final M5VectorProperties vectorProperties;
  private final M5CopilotProperties copilotProperties;

  public M5CopilotService(
      M5EmbeddingPort embeddingPort,
      M5ChatPort chatPort,
      PolicySearchPort policySearchPort,
      M5VectorProperties vectorProperties,
      M5CopilotProperties copilotProperties) {
    this.embeddingPort = embeddingPort;
    this.chatPort = chatPort;
    this.policySearchPort = policySearchPort;
    this.vectorProperties = vectorProperties;
    this.copilotProperties = copilotProperties;
  }

  @Transactional(readOnly = true)
  public CopilotAnswerResponse ask(CopilotRequest request) {
    float[] queryEmbedding = embeddingPort.embedQuery(request.question());
    List<PolicyMatch> matches =
        policySearchPort.search(queryEmbedding, vectorProperties.embeddingSpaceId(), TOP_K);
    if (matches.isEmpty() || matches.get(0).distance() > copilotProperties.maxDistance()) {
      return new CopilotAnswerResponse(
          "I can help with FluxPay compliance, KYC, AML, payment-review, country-rule, and payout-support questions. Please ask a question about an indexed policy.",
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
    return new CopilotAnswerResponse(chatPort.answer(request.question(), sources), sources);
  }

  private static String excerpt(String content, int maxLength) {
    String trimmed = content.trim();
    return trimmed.length() <= maxLength
        ? trimmed
        : trimmed.substring(0, maxLength).trim() + "...";
  }
}
