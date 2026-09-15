package com.fluxpay.service;
import com.fluxpay.config.M5ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
public final class M5PolicyChunker {
  private static final int MAX_BYTES = 64 * 1024;
  private static final int MAX_DOCUMENT_WORDS = 5000;
  private static final int MAX_CHUNKS = 16;
  private static final Pattern WORD = Pattern.compile("(?U)\\S+");
  private final int min;
  private final int max;
  private final int overlap;

  public M5PolicyChunker(int min, int max, int overlap) {
    if (min <= 0 || max < min || overlap < 0 || overlap >= min) {
      throw new IllegalArgumentException("Invalid chunk limits");
    }
    this.min = min;
    this.max = max;
    this.overlap = overlap;
  }

  public static String normalize(String text) {
    if (text == null) {
      return null;
    }
    return Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'),
        Normalizer.Form.NFC).strip();
  }

  public static String hash(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  public List<String> chunks(String text) {
    String canonical = normalize(text);
    if (canonical == null || canonical.isBlank()) {
      throw validation("Policy content is required");
    }
    if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw validation("Policy content exceeds 64 KiB");
    }
    var matcher=WORD.matcher(canonical);
    List<Word> words=new ArrayList<>();
    while(matcher.find()) words.add(new Word(matcher.start(),matcher.end(),matcher.group()));
    if(words.isEmpty()) throw validation("Policy content is required");
    if (words.size() > MAX_DOCUMENT_WORDS) {
      throw validation("Policy content exceeds 5000 words");
    }
    if (words.size() <= max) {
      return List.of(canonical);
    }

    List<String> result = new ArrayList<>();
    int start = 0;
    while (start < words.size()) {
      int end = Math.min(start + max, words.size());
      if (end < words.size()) {
        int firstAllowedBoundary = start + min;
        for (int candidate = firstAllowedBoundary; candidate < end; candidate++) {
          if (endsSentence(words.get(candidate - 1).text())) {
            end = candidate;
            break;
          }
        }
      }
      result.add(canonical.substring(words.get(start).start(),words.get(end-1).end()));
      if (result.size() > MAX_CHUNKS) {
        throw validation("Policy content exceeds 16 chunks");
      }
      if (end == words.size()) {
        break;
      }
      int next = end - overlap;
      if (next <= start) {
        throw validation("Policy chunking made no progress");
      }
      start = next;
    }
    return List.copyOf(result);
  }

  private static boolean endsSentence(String word) {
    int index = word.length() - 1;
    while (index >= 0 && "\"'\u2019\u201d)]}".indexOf(word.charAt(index)) >= 0) {
      index--;
    }
    return index >= 0 && ".!?".indexOf(word.charAt(index)) >= 0;
  }

  private static M5ApiException validation(String message) {
    return new M5ApiException(400, "VALIDATION", message);
  }

  private record Word(int start,int end,String text) {}
}
