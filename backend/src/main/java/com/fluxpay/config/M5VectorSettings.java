package com.fluxpay.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.core.env.Environment;

/** Configuration for the approved 768-dimensional M5 policy vector space. */
public record M5VectorSettings(String mode, String url, String model, String providerVersion,
    String apiKey, int dimensions, int timeoutSeconds, int minWords, int maxWords,
    int overlapWords, int topK, double maxDistance) {
  private static final int REQUIRED_DIMENSIONS = 768;

  public M5VectorSettings {
    mode = required(mode, "embedding mode").toLowerCase(Locale.ROOT);
    if (!mode.equals("mock") && !mode.equals("ollama") && !mode.equals("api")) {
      throw new IllegalArgumentException("Embedding mode must be mock, ollama, or api");
    }
    url = url == null ? "" : url.trim();
    model = required(model, "embedding model");
    providerVersion = required(providerVersion, "embedding provider revision");
    apiKey = apiKey == null ? "" : apiKey;
    if (dimensions != REQUIRED_DIMENSIONS) {
      throw new IllegalArgumentException("M5 embedding dimensions must be 768");
    }
    if (timeoutSeconds <= 0) {
      throw new IllegalArgumentException("Embedding timeout must be positive");
    }
    if (minWords <= 0 || maxWords < minWords || overlapWords < 0 || overlapWords >= minWords) {
      throw new IllegalArgumentException("Invalid policy chunk limits");
    }
    if (topK < 1 || topK > 5) {
      throw new IllegalArgumentException("Policy top-k must be between 1 and 5");
    }
    if (!Double.isFinite(maxDistance) || maxDistance < 0 || maxDistance > 2) {
      throw new IllegalArgumentException("Policy maximum distance must be between 0 and 2");
    }
    if (!mode.equals("mock")) {
      validateUrl(url);
    }
    if (mode.equals("api") && apiKey.isBlank()) {
      throw new IllegalArgumentException("API embedding mode requires an API key");
    }
  }

  public static M5VectorSettings from(Environment env) {
    return new M5VectorSettings(
        value(env, "fluxpay.embedding-mode", "ollama"),
        value(env, "embedding.url", value(env, "fluxpay.embedding-api-url",
            "http://localhost:11434/api/embed")),
        value(env, "embedding.model", value(env, "fluxpay.embedding-model", "nomic-embed-text")),
        value(env, "embedding.provider-version", "1"),
        value(env, "embedding.api-key", value(env, "fluxpay.embedding-api-key", "")),
        integer(env, "embedding.expected-dimensions", 768),
        integer(env, "embedding.timeout-seconds", 3),
        integer(env, "policy.chunk-min-words", 400),
        integer(env, "policy.chunk-max-words", 700),
        integer(env, "policy.chunk-overlap-words", 50),
        integer(env, "policy.top-k", 5),
        decimal(env, "policy.max-distance", .35));
  }

  public String spaceId() {
    String identity = mode + "\n" + model + "\n" + providerVersion + "\n" + dimensions
        + "\nnomic-search-prefix-v1";
    try {
      return "m5-" + HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static String value(Environment env, String key, String fallback) {
    return env.getProperty(key, fallback);
  }

  private static int integer(Environment env, String key, int fallback) {
    return env.getProperty(key, Integer.class, fallback);
  }

  private static double decimal(Environment env, String key, double fallback) {
    return env.getProperty(key, Double.class, fallback);
  }

  private static String required(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " is required");
    }
    return value.trim();
  }

  private static void validateUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!uri.isAbsolute() || uri.getHost() == null
          || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
        throw new IllegalArgumentException("Embedding URL must be an absolute HTTP(S) URL");
      }
    } catch (IllegalArgumentException e) {
      if ("Embedding URL must be an absolute HTTP(S) URL".equals(e.getMessage())) throw e;
      throw new IllegalArgumentException("Embedding URL must be an absolute HTTP(S) URL", e);
    }
  }
}
