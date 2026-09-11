package com.fluxpay.service;

/**
 * Turns text into a fixed-length embedding vector for Oracle AI Vector Search. Two
 * implementations exist: {@link MockEmbeddingProvider} (default, deterministic, no external
 * calls) and {@link ApiEmbeddingProvider} (real semantic embeddings via an OpenAI-compatible
 * endpoint, opt-in via {@code fluxpay.embedding-mode=api}).
 */
public interface EmbeddingProvider {

  float[] embed(String text);

  /**
   * Embeds a piece of text that is being <em>stored</em> for later retrieval (a policy chunk).
   * Providers that were trained with asymmetric document/query prefixes (e.g. Ollama's {@code
   * nomic-embed-text}) should override this to apply the document-side prefix. Defaults to {@link
   * #embed(String)} for providers that don't distinguish.
   */
  default float[] embedDocument(String text) {
    return embed(text);
  }

  /**
   * Embeds a piece of text that is a <em>search query</em> (a Copilot question). See {@link
   * #embedDocument(String)}.
   */
  default float[] embedQuery(String text) {
    return embed(text);
  }

  int dimensions();
}
