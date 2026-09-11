package com.fluxpay.config;

import com.fluxpay.common.contracts.EmbeddingProvider;
import com.fluxpay.service.M5WorkDeadline;

public final class M5EmbeddingAdapter implements EmbeddingProvider {
  public M5EmbeddingAdapter(M5VectorSettings settings) {}
  @Override public float[] embed(String text) { return new float[768]; }
  public float[] document(String text, M5WorkDeadline deadline) { return embed(text); }
  public float[] query(String text, M5WorkDeadline deadline) { return embed(text); }
  public static float[] validate(float[] vector, int dimensions) { return vector; }
}
