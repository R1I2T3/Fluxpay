package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fluxpay.config.M5ApiException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class M5PolicyChunkerUnitTest {
  @Test void canonicalContentRetainsInternalWhitespaceAndNormalizesUnicodeAndLines() {
    assertEquals("Café\nA  B", M5PolicyChunker.normalize(" \r\nCafe\u0301\r\nA  B\r "));
    assertEquals("185f8db32271fe25f561a6fc938b2e264306ec304eda518007d1764826381969", M5PolicyChunker.hash("Hello"));
  }
  @Test void oversizedSentenceSplitsWithExactOverlapAndKeepsTail() {
    var words = java.util.stream.IntStream.rangeClosed(1, 1000).mapToObj(i -> "w"+i).toList();
    var chunks = new M5PolicyChunker(400,700,50).chunks(String.join(" ", words));
    assertEquals(2, chunks.size());
    assertEquals(700,chunks.get(0).split(" ").length);
    assertTrue(chunks.get(1).startsWith("w651 "));
    assertTrue(chunks.get(1).endsWith("w1000"));
  }
  @Test void prefersSentenceBoundaryAfterMinimumAndAcceptsShortDocument() {
    var chunker = new M5PolicyChunker(4,7,2);
    assertEquals(java.util.List.of("one two three four.", "three four. five six seven eight nine."), chunker.chunks("one two three four. five six seven eight nine."));
    assertEquals(java.util.List.of("Small policy."), chunker.chunks("Small policy."));
  }
  @Test void rejectsNoProgressAndEveryWorkloadLimitBeforeEmbedding() {
    assertThrows(IllegalArgumentException.class, () -> new M5PolicyChunker(5,10,5));
    var c = new M5PolicyChunker(400,700,50);
    assertEquals("VALIDATION", assertThrows(M5ApiException.class, () -> c.chunks("word ".repeat(5001))).code());
    assertThrows(M5ApiException.class, () -> c.chunks("é".repeat(32769)));
    assertThrows(M5ApiException.class, () -> new M5PolicyChunker(2,3,1).chunks("word ".repeat(40)));
  }
}
