package com.fluxpay.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Splits policy content into chunks of roughly 400-700 words with a small overlap, per the
 * product spec (section 10.6). Package-private: only {@link PolicyIndexingService} uses this.
 */
final class TextChunker {

  private static final int TARGET_WORDS = 550;
  private static final int MAX_WORDS = 700;
  private static final int OVERLAP_WORDS = 50;

  private TextChunker() {}

  static List<String> chunk(String content) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    String[] words = content.trim().split("\\s+");
    if (words.length <= MAX_WORDS) {
      return List.of(String.join(" ", words));
    }

    List<String> chunks = new ArrayList<>();
    int start = 0;
    while (start < words.length) {
      int size = Math.min(TARGET_WORDS, words.length - start);
      int end = start + size;
      chunks.add(String.join(" ", Arrays.asList(words).subList(start, end)));
      if (end >= words.length) {
        break;
      }
      start = Math.max(end - OVERLAP_WORDS, start + 1);
    }
    return chunks;
  }
}
