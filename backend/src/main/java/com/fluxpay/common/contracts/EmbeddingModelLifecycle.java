package com.fluxpay.common.contracts;

/** Releases embedding-provider resources after the policy corpus has been rebuilt. */
public interface EmbeddingModelLifecycle {
  void unload();
}
