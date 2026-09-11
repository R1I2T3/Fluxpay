package com.fluxpay.config;

import org.springframework.core.env.Environment;

/** Configuration for the approved 768-dimensional M5 policy vector space. */
public record M5VectorSettings(String mode, String url, String model, String providerVersion,
    String apiKey, int dimensions, int timeoutSeconds, int minWords, int maxWords,
    int overlapWords, int topK, double maxDistance) {
  public static M5VectorSettings from(Environment env) { return null; }
  public String spaceId() { return ""; }
}
