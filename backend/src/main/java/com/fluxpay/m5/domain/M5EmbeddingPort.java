package com.fluxpay.m5.domain;

/** Produces the separate document and query vectors used by M5 policy retrieval. */
public interface M5EmbeddingPort {
  float[] embedDocument(String document);

  float[] embedQuery(String query);
}
