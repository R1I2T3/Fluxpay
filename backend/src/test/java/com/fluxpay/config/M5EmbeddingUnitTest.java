package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;
import com.fluxpay.service.M5WorkDeadline;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class M5EmbeddingUnitTest {
  @Test void providerSelectionRejectsUnknownModesAndNon768Dimensions() {
    assertThrows(IllegalArgumentException.class, () -> M5VectorSettings.from(new MockEnvironment().withProperty("fluxpay.embedding-mode","unknown")));
    assertThrows(IllegalArgumentException.class, () -> M5VectorSettings.from(new MockEnvironment().withProperty("embedding.expected-dimensions","1536")));
    assertThrows(IllegalArgumentException.class, () -> M5VectorSettings.from(new MockEnvironment().withProperty("policy.top-k","6")));
    var defaults=assertDoesNotThrow(() -> M5VectorSettings.from(new MockEnvironment()));
    assertNotNull(defaults);
    assertEquals(768, defaults.dimensions());
    assertEquals("mock", defaults.mode());
    assertNotEquals(defaults.spaceId(), M5VectorSettings.from(new MockEnvironment().withProperty("embedding.provider-version","2")).spaceId());
  }
  @Test void mockReturnsDeterministicNonzeroUnitVectorAndDifferentInputsDiffer() {
    var provider=new M5EmbeddingAdapter(settings("mock", "", ""));
    float[] a=provider.embed("Synthetic identity policy.");
    assertEquals(768,a.length);
    double norm=0; for(float x:a) norm+=x*x;
    assertEquals(1d,norm,0.00001);
    assertArrayEquals(a,provider.embed("Synthetic identity policy."));
    assertFalse(java.util.Arrays.equals(a,provider.embed("Other policy.")));
  }
  @Test void rejectsInvalidProviderVectors() {
    for(float[] v:new float[][]{null,new float[768],new float[]{1},filled(Float.NaN),filled(Float.POSITIVE_INFINITY)}) {
      assertEquals("EMBEDDING_INVALID", assertThrows(M5ApiException.class, () -> M5EmbeddingAdapter.validate(v,768)).code());
    }
  }
  @Test void modernOllamaUsesCompleteUrlAndAsymmetricPrefixes() throws Exception {
    var request=new AtomicReference<String>(); var path=new AtomicReference<String>();
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/custom/embed", e -> {
      path.set(e.getRequestURI().toString()); request.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
      byte[] response=("{\"embeddings\":[[1"+",0".repeat(767)+"]]}").getBytes(StandardCharsets.UTF_8);
      e.sendResponseHeaders(200,response.length); e.getResponseBody().write(response); e.close();
    }); server.start();
    try {
      var p=new M5EmbeddingAdapter(settings("ollama","http://127.0.0.1:"+server.getAddress().getPort()+"/custom/embed",""));
      assertEquals(1, p.document("Synthetic policy.",deadline())[0]);
      assertTrue(request.get().contains("search_document: Synthetic policy."));
      assertEquals("/custom/embed",path.get());
      assertEquals(1,p.query("Question",deadline())[0]);
      assertTrue(request.get().contains("search_query: Question"));
    } finally { server.stop(0); }
  }
  @Test void apiAcceptsOpenAiResponseAndMapsHttpFailureWithoutFallback() throws Exception {
    var auth=new AtomicReference<String>();
    HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/embeddings", e -> {
      auth.set(e.getRequestHeaders().getFirst("Authorization"));
      byte[] response=("{\"data\":[{\"embedding\":[1"+",0".repeat(767)+"]}]}").getBytes(StandardCharsets.UTF_8);
      e.sendResponseHeaders(200,response.length); e.getResponseBody().write(response); e.close();
    });
    server.createContext("/failed", e -> { e.sendResponseHeaders(503,-1); e.close(); }); server.start();
    try {
      String base="http://127.0.0.1:"+server.getAddress().getPort();
      assertEquals(1,new M5EmbeddingAdapter(settings("api",base+"/embeddings","synthetic-test-key")).embed("policy")[0]);
      assertEquals("Bearer synthetic-test-key",auth.get());
      assertEquals("EMBEDDING_UNAVAILABLE",assertThrows(M5ApiException.class,()->new M5EmbeddingAdapter(settings("api",base+"/failed","synthetic-test-key")).embed("policy")).code());
    } finally { server.stop(0); }
  }
  @Test void exhaustedOverallDeadlineStopsWorkAndCapsProviderAndSqlBudgets() {
    var now=new java.util.concurrent.atomic.AtomicLong();
    var d=new M5WorkDeadline(Duration.ofSeconds(5),"COPILOT_TIMEOUT",now::get);
    assertEquals(Duration.ofSeconds(3),d.providerTimeout(3));
    now.set(4_500_000_000L);
    assertEquals(Duration.ofMillis(500),d.providerTimeout(3));
    assertEquals(1,d.sqlTimeoutSeconds());
    now.set(5_000_000_000L);
    assertEquals("COPILOT_TIMEOUT",assertThrows(M5ApiException.class,d::check).code());
  }
  static M5VectorSettings settings(String mode,String url,String key) { return new M5VectorSettings(mode,url,"nomic-embed-text","1",key,768,3,400,700,50,5,.35); }
  static float[] filled(float value) { var a=new float[768]; a[0]=value; return a; }
  static M5WorkDeadline deadline() { return new M5WorkDeadline(Duration.ofSeconds(5),"COPILOT_TIMEOUT"); }
}
