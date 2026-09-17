package com.fluxpay.service;

import java.util.ArrayList;
import java.util.List;

/** Deterministically splits policy text into a bounded set of retrieval-friendly passages. */
public class PolicyChunker {
  private static final int MAX_CHUNK_CHARACTERS = 1_000;
  private static final int MAX_CHUNKS = 16;

  public List<String> chunk(String content) {
    if (content == null || content.isBlank()) {
      throw new IllegalArgumentException("Policy content is required for indexing");
    }
    List<String> chunks = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (String sentence : content.trim().split("(?<=[.!?])\\s+")) {
      String normalized = sentence.trim().replaceAll("\\s+", " ");
      if (normalized.isEmpty()) {
        continue;
      }
      if (current.length() > 0
          && current.length() + normalized.length() + 1 > MAX_CHUNK_CHARACTERS) {
        addChunk(chunks, current);
      }
      if (normalized.length() > MAX_CHUNK_CHARACTERS) {
        addLongSentence(chunks, normalized);
      } else {
        if (current.length() > 0) {
          current.append(' ');
        }
        current.append(normalized);
      }
    }
    addChunk(chunks, current);
    return List.copyOf(chunks);
  }

  private static void addLongSentence(List<String> chunks, String sentence) {
    for (int start = 0; start < sentence.length(); start += MAX_CHUNK_CHARACTERS) {
      int end = Math.min(start + MAX_CHUNK_CHARACTERS, sentence.length());
      addChunk(chunks, new StringBuilder(sentence.substring(start, end)));
    }
  }

  private static void addChunk(List<String> chunks, StringBuilder content) {
    if (content.length() == 0) {
      return;
    }
    if (chunks.size() == MAX_CHUNKS) {
      throw new IllegalArgumentException(
          "Policy content exceeds the maximum of " + MAX_CHUNKS + " chunks");
    }
    chunks.add(content.toString());
    content.setLength(0);
  }
}
