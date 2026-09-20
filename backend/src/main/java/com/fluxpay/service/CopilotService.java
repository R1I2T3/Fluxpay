package com.fluxpay.service;

import com.fluxpay.common.contracts.ChatPort;
import com.fluxpay.common.contracts.EmbeddingPort;
import com.fluxpay.common.contracts.PolicySearchPort;
import com.fluxpay.config.CopilotProperties;
import com.fluxpay.config.VectorProperties;
import com.fluxpay.dto.CopilotAnswerResponse;
import com.fluxpay.dto.CopilotRequest;
import com.fluxpay.dto.CopilotSource;
import com.fluxpay.dto.PolicyMatch;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds cited, extractive compliance answers from the current indexed policy corpus. */
@Service
public class CopilotService {
  private final EmbeddingPort embeddingPort;
  private final ChatPort chatPort;
  private final PolicySearchPort policySearchPort;
  private final VectorProperties vectorProperties;
  private final CopilotProperties copilotProperties;

  public CopilotService(
      EmbeddingPort embeddingPort,
      ChatPort chatPort,
      PolicySearchPort policySearchPort,
      VectorProperties vectorProperties,
      CopilotProperties copilotProperties) {
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
        policySearchPort.search(
            queryEmbedding, vectorProperties.embeddingSpaceId(), copilotProperties.maxSources());
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
                        match.content().trim()))
            .toList();
    return new CopilotAnswerResponse(chatPort.answer(request.question(), sources), sources);
  }

  @Transactional(readOnly = true)
  public void stream(CopilotRequest request, Consumer<String> onDelta) {
    float[] queryEmbedding = embeddingPort.embedQuery(request.question());
    List<PolicyMatch> matches =
        policySearchPort.search(
            queryEmbedding, vectorProperties.embeddingSpaceId(), copilotProperties.maxSources());
    if (matches.isEmpty() || matches.get(0).distance() > copilotProperties.maxDistance()) {
      onDelta.accept(
          "I can help with FluxPay compliance, KYC, AML, payment-review, country-rule, and payout-support questions. Please ask a question about an indexed policy.");
      return;
    }
    List<CopilotSource> sources =
        matches.stream()
            .map(
                match ->
                    new CopilotSource(
                        match.policyDocumentId(),
                        match.title(),
                        match.chunkNumber(),
                        match.content().trim()))
            .toList();
    chatPort.stream(request.question(), sources, onDelta);
  }

}
