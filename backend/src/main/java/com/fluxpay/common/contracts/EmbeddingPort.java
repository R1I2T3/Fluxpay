package com.fluxpay.common.contracts;

/** Produces the separate document and query vectors used by policy retrieval. */
public interface EmbeddingPort {
  float[] embedDocument(String document);

  float[] embedQuery(String query);
}
