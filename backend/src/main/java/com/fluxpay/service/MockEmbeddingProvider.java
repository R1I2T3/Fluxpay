package com.fluxpay.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Deterministic, test-only embedding provider -- the default so vector search and the Copilot can
 * be exercised end-to-end with zero configuration and no API key.
 *
 * <p>The vector is unit-length and the right dimensionality, so it behaves like a real embedding
 * for storage/search plumbing, but it carries no real semantic meaning: the same text always
 * produces the same vector (seeded from its SHA-256 hash), so cosine distance reflects whether two
 * pieces of text are byte-identical, not whether they mean the same thing. Do not treat search
 * results from this provider as semantically accurate -- switch to {@link ApiEmbeddingProvider}
 * ({@code fluxpay.embedding-mode=api}) for that.
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Component
@ConditionalOnProperty(
    name = "fluxpay.embedding-mode",
    havingValue = "mock",
    matchIfMissing = true)
public class MockEmbeddingProvider implements EmbeddingProvider {

  private final int dimensions;

  public MockEmbeddingProvider(@Value("${fluxpay.embedding-dimensions:768}") int dimensions) {
    this.dimensions = dimensions;
  }

  @Override
  public float[] embed(String text) {
    Random random = new Random(seedFrom(text));
    float[] vector = new float[dimensions];
    double sumSquares = 0;
    for (int i = 0; i < dimensions; i++) {
      float value = (float) random.nextGaussian();
      vector[i] = value;
      sumSquares += (double) value * value;
    }
    float norm = (float) Math.sqrt(sumSquares);
    if (norm > 0) {
      for (int i = 0; i < dimensions; i++) {
        vector[i] /= norm;
      }
    }
    return vector;
  }

  @Override
  public int dimensions() {
    return dimensions;
  }

  private long seedFrom(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      long seed = 0;
      for (int i = 0; i < 8; i++) {
        seed = (seed << 8) | (hash[i] & 0xffL);
      }
      return seed;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
