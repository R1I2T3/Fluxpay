package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class MockSegregationInventoryTest {
  @Test
  void noProductionClassIsGatedOnMockOrLocalProfile() throws Exception {
    Path preferred = Path.of("backend/src/main/java/com/fluxpay");
    Path fallback = Path.of("src/main/java/com/fluxpay");
    Path root;
    if (Files.isDirectory(preferred)) {
      root = preferred;
    } else if (Files.isDirectory(fallback)) {
      root = fallback;
    } else {
      fail("production sources dir missing: tried " + preferred + " and " + fallback);
      return;
    }
    List<String> gated;
    try (Stream<Path> files = Files.walk(root)) {
      gated =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .filter(
                  p -> {
                    try {
                      String src = Files.readString(p);
                      return src.contains("@Profile(\"mock\")")
                          || src.contains("@Profile(\"local\")")
                          || src.contains("@Profile(\"!local\")");
                    } catch (Exception e) {
                      return false;
                    }
                  })
              .map(p -> p.getFileName().toString())
              .sorted()
              .toList();
    }
    assertTrue(gated.isEmpty(), "mock-gated production classes remain: " + gated);
  }
}
