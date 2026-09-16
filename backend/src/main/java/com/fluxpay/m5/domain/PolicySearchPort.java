package com.fluxpay.m5.domain;

import java.util.List;

/** Retrieves cited chunks only from an indexed policy document's active vector generation. */
public interface PolicySearchPort {
  List<PolicyMatch> search(float[] queryEmbedding, String embeddingSpaceId, int limit);
}
