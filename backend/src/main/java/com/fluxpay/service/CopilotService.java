package com.fluxpay.service;

import com.fluxpay.dto.CopilotAnswerResponse;
import com.fluxpay.dto.CopilotRequest;
import com.fluxpay.dto.CopilotSource;
import com.fluxpay.repository.PolicyVectorRepository;
import com.fluxpay.repository.PolicyVectorRepository.ChunkMatch;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Compliance Copilot (product spec section 10.6): embeds the admin's question, retrieves the
 * nearest indexed policy chunks via Oracle AI Vector Search, and builds the answer from the
 * retrieved text.
 *
 * <p>Two answer modes, controlled by {@code fluxpay.copilot-generation-mode}:
 *
 * <ul>
 *   <li><b>{@code extractive}</b> (default) -- the spec's own flow ("Build answer from retrieved
 *       chunks. Return title/chunk excerpts as sources") describes an extractive answer, not a
 *       generated one, so by default the answer is the best-matching chunk's own text. This needs
 *       no LLM and can never hallucinate a policy that doesn't exist.
 *   <li><b>{@code ollama}</b> -- asks a local Ollama chat model ({@link OllamaAnswerGenerator}) to
 *       phrase the same retrieved chunks as natural prose. Still strictly grounded (the prompt
 *       forbids answering outside the supplied excerpts) and falls back to the extractive answer
 *       automatically if the model call fails for any reason.
 * </ul>
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Service
public class CopilotService {

  private static final Logger log = LoggerFactory.getLogger(CopilotService.class);
  private static final int TOP_K = 5;

  private final EmbeddingProvider embeddingProvider;
  private final PolicyVectorRepository vectorRepository;
  private final OllamaAnswerGenerator answerGenerator;
  private final boolean useGeneratedAnswers;

  public CopilotService(
      EmbeddingProvider embeddingProvider,
      PolicyVectorRepository vectorRepository,
      OllamaAnswerGenerator answerGenerator,
      @Value("${fluxpay.copilot-generation-mode:extractive}") String generationMode) {
    this.embeddingProvider = embeddingProvider;
    this.vectorRepository = vectorRepository;
    this.answerGenerator = answerGenerator;
    this.useGeneratedAnswers = "ollama".equalsIgnoreCase(generationMode);
  }

  @Transactional(readOnly = true)
  public CopilotAnswerResponse ask(CopilotRequest request) {
    float[] questionEmbedding = embeddingProvider.embedQuery(request.question());
    List<ChunkMatch> matches = vectorRepository.findNearest(questionEmbedding, TOP_K);

    if (matches.isEmpty()) {
      return new CopilotAnswerResponse(
          "I couldn't find an indexed policy for this question yet. Upload and index a relevant "
              + "policy first, or try asking about KYC verification, new recipients, high-value "
              + "payments, payment holds, or country transfer rules.",
          List.of());
    }

    List<CopilotSource> sources =
        matches.stream()
            .map(
                m ->
                    new CopilotSource(
                        m.documentId(), m.title(), m.chunkNumber(), excerpt(m.content(), 200)))
            .toList();

    String answer =
        useGeneratedAnswers
            ? generatedAnswer(request.question(), matches)
            : extractiveAnswer(matches);

    return new CopilotAnswerResponse(answer, sources);
  }

  private String extractiveAnswer(List<ChunkMatch> matches) {
    ChunkMatch best = matches.get(0);
    return "Based on the \"" + best.title() + "\" policy: " + excerpt(best.content(), 320);
  }

  private String generatedAnswer(String question, List<ChunkMatch> matches) {
    try {
      List<String> excerpts =
          matches.stream().map(m -> "(" + m.title() + ") " + m.content()).toList();
      return answerGenerator.generate(question, excerpts);
    } catch (Exception e) {
      log.warn(
          "Ollama answer generation failed ({}); falling back to the extractive answer.",
          e.getMessage());
      return extractiveAnswer(matches);
    }
  }

  private String excerpt(String text, int maxChars) {
    String trimmed = text.trim();
    return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars).trim() + "...";
  }
}
