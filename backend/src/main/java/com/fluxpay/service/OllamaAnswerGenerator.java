package com.fluxpay.service;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Optional: asks a local Ollama <em>chat</em> model to phrase the Copilot's answer in natural
 * language, strictly grounded in the retrieved policy chunks it is handed.
 *
 * <p><b>Off by default.</b> {@link CopilotService} only calls this when {@code
 * fluxpay.copilot-generation-mode=ollama}, and wraps the call in a try/catch that falls back to
 * the plain extractive answer if this throws for any reason (model not pulled, Ollama not
 * running, request timeout, etc). The Copilot therefore never breaks because of this class -- it
 * only ever makes the phrasing nicer when everything is working.
 *
 * <p><b>Do you need a bigger model for this part?</b> Maybe, but not necessarily a "bigger" one --
 * a general-purpose <em>instruction-following</em> one. {@code qwen2.5-coder:7b} (already pulled,
 * per the setup notes) is a code-specialised model; it will follow the prompt below well enough
 * for a demo, but a small instruct model such as {@code llama3.2} (3B) or {@code
 * qwen2.5:7b-instruct} typically reads more naturally for this kind of prose-answer task and is
 * no larger. Pull whichever you prefer and point {@code fluxpay.copilot-generation-model} at it.
 * Parameter count matters far less here than for coding -- the prompt below restricts the model to
 * only the supplied excerpts, so even a 3B instruct model is usually sufficient.
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Component
public class OllamaAnswerGenerator {

  private final RestClient restClient;
  private final String model;

  public OllamaAnswerGenerator(
      @Value("${fluxpay.embedding-api-url:http://localhost:11434}") String baseUrl,
      @Value("${fluxpay.copilot-generation-model:qwen2.5-coder:7b}") String model) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(5_000);
    requestFactory.setReadTimeout(45_000);
    this.restClient =
        RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.model = model;
  }

  /**
   * Composes a grounded answer from the question plus already-retrieved chunk texts. The prompt
   * explicitly forbids answering from outside the supplied excerpts and instructs the model to
   * say so plainly if they don't cover the question -- so this can restate a policy, but it
   * cannot invent one.
   */
  public String generate(String question, List<String> chunkExcerpts) {
    StringBuilder context = new StringBuilder();
    for (int i = 0; i < chunkExcerpts.size(); i++) {
      context.append('[').append(i + 1).append("] ").append(chunkExcerpts.get(i)).append("\n\n");
    }
    String prompt =
        "You are a compliance assistant for a cross-border payments company. Answer the question "
            + "using ONLY the numbered policy excerpts below. Cite the excerpt number(s) you used "
            + "in square brackets, e.g. [1]. Keep the answer to 2-4 sentences. If the excerpts do "
            + "not contain the answer, say so plainly instead of guessing -- never state a policy, "
            + "threshold, or rule that is not written in the excerpts.\n\n"
            + "Policy excerpts:\n"
            + context
            + "Question: "
            + question
            + "\nAnswer:";

    Map<String, Object> requestBody = Map.of("model", model, "prompt", prompt, "stream", false);
    Map<String, Object> response =
        restClient
            .post()
            .uri("/api/generate")
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(Map.class);
    Object text = response == null ? null : response.get("response");
    if (text == null || text.toString().isBlank()) {
      throw new IllegalStateException("Ollama returned an empty generation response");
    }
    return text.toString().trim();
  }
}
