package com.fluxpay.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.contracts.EmbeddingProvider;
import com.fluxpay.service.M5WorkDeadline;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class M5EmbeddingAdapter implements EmbeddingProvider {
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final ObjectMapper JSON = new ObjectMapper();
  private final M5VectorSettings settings;
  private final HttpClient client;

  public M5EmbeddingAdapter(M5VectorSettings settings) {
    this.settings = settings;
    this.client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(Math.min(3, settings.timeoutSeconds())))
        .build();
  }

  @Override
  public float[] embed(String text) {
    return document(text, new M5WorkDeadline(
        Duration.ofSeconds(settings.timeoutSeconds()), "EMBEDDING_TIMEOUT"));
  }

  public float[] document(String text, M5WorkDeadline deadline) {
    return invoke(text, true, deadline);
  }

  public float[] query(String text, M5WorkDeadline deadline) {
    return invoke(text, false, deadline);
  }

  public static float[] validate(float[] vector, int dimensions) {
    if (vector == null || vector.length != dimensions) {
      throw invalid("Embedding vector has the wrong dimensions");
    }
    boolean nonzero = false;
    for (float value : vector) {
      if (!Float.isFinite(value)) {
        throw invalid("Embedding vector contains a non-finite value");
      }
      nonzero |= value != 0f;
    }
    if (!nonzero) {
      throw invalid("Embedding vector must not be zero");
    }
    return vector;
  }

  private float[] invoke(String text, boolean document, M5WorkDeadline deadline) {
    if (text == null || text.isBlank()) {
      throw new M5ApiException(400, "VALIDATION", "Embedding text is required");
    }
    deadline.check();
    if (settings.mode().equals("mock")) {
      return validate(mock(text), settings.dimensions());
    }
    String prepared = settings.mode().equals("ollama")
        ? (document ? "search_document: " : "search_query: ") + text : text;
    Duration timeout = deadline.providerTimeout(settings.timeoutSeconds());
    CompletableFuture<HttpResponse<byte[]>> exchange = null;
    try {
      Map<String,Object> payload = new LinkedHashMap<>();
      payload.put("model", settings.model());
      payload.put("input", prepared);
      if (settings.mode().equals("ollama")) payload.put("truncate", false);
      String body = JSON.writeValueAsString(payload);
      HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(settings.url()))
          .timeout(timeout)
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
      if (settings.mode().equals("api")) {
        request.header("Authorization", "Bearer " + settings.apiKey());
      }
      exchange = client.sendAsync(request.build(), ignored -> new LimitedBodySubscriber());
      HttpResponse<byte[]> response = exchange.get(
          Math.max(1L, timeout.toNanos()), TimeUnit.NANOSECONDS);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw unavailable("Embedding provider returned HTTP " + response.statusCode(), null);
      }
      return validate(parse(new String(response.body(), StandardCharsets.UTF_8)), settings.dimensions());
    } catch (TimeoutException e) {
      cancel(exchange);
      throw timeout(e);
    } catch (InterruptedException e) {
      cancel(exchange);
      Thread.currentThread().interrupt();
      throw unavailable("Embedding provider call was interrupted", e);
    } catch (ExecutionException | CompletionException e) {
      Throwable cause = unwrap(e);
      if (cause instanceof java.net.http.HttpTimeoutException) {
        throw timeout(cause);
      }
      if (cause instanceof ResponseTooLargeException) {
        throw invalid("Embedding provider response exceeds 1 MiB", cause);
      }
      throw unavailable("Embedding provider is unavailable", cause);
    } catch (IOException e) {
      throw invalid("Embedding provider returned malformed JSON", e);
    } catch (M5ApiException e) {
      throw e;
    } catch (RuntimeException e) {
      throw invalid("Embedding provider returned a malformed response", e);
    }
  }

  private float[] parse(String body) throws IOException {
    JsonNode root = JSON.readTree(body);
    JsonNode embedding = settings.mode().equals("ollama")
        ? root.path("embeddings").path(0)
        : root.path("data").path(0).path("embedding");
    if (!embedding.isArray()) {
      throw invalid("Embedding provider response contains no vector");
    }
    float[] vector = new float[embedding.size()];
    for (int i = 0; i < embedding.size(); i++) {
      if (!embedding.get(i).isNumber()) {
        throw invalid("Embedding provider vector contains a non-number");
      }
      vector[i] = embedding.get(i).floatValue();
    }
    return vector;
  }

  private float[] mock(String text) {
    Random random = new Random(seed(text));
    float[] vector = new float[settings.dimensions()];
    double squared = 0;
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) random.nextGaussian();
      squared += (double) vector[i] * vector[i];
    }
    double norm = Math.sqrt(squared);
    for (int i = 0; i < vector.length; i++) {
      vector[i] = (float) (vector[i] / norm);
    }
    return vector;
  }

  private static long seed(String text) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(text.getBytes(StandardCharsets.UTF_8));
      long seed = 0;
      for (int i = 0; i < Long.BYTES; i++) {
        seed = (seed << 8) | (digest[i] & 0xffL);
      }
      return seed;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static M5ApiException invalid(String message) {
    return invalid(message, null);
  }

  private static M5ApiException invalid(String message, Throwable cause) {
    M5ApiException error = new M5ApiException(502, "EMBEDDING_INVALID", message);
    if (cause != null) error.initCause(cause);
    return error;
  }

  private static M5ApiException unavailable(String message, Throwable cause) {
    M5ApiException error = new M5ApiException(503, "EMBEDDING_UNAVAILABLE", message);
    if (cause != null) error.initCause(cause);
    return error;
  }

  private static M5ApiException timeout(Throwable cause) {
    M5ApiException error = new M5ApiException(504, "EMBEDDING_TIMEOUT",
        "Embedding provider timed out");
    error.initCause(cause);
    return error;
  }

  private static void cancel(CompletableFuture<?> exchange) {
    if (exchange != null) exchange.cancel(true);
  }

  private static Throwable unwrap(Throwable error) {
    Throwable current = error;
    while ((current instanceof ExecutionException || current instanceof CompletionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static final class ResponseTooLargeException extends RuntimeException {}

  private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    @Override
    public CompletionStage<byte[]> getBody() {
      return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription value) {
      subscription = value;
      value.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (body.isDone()) return;
      for (ByteBuffer buffer : buffers) {
        if ((long) bytes.size() + buffer.remaining() > MAX_RESPONSE_BYTES) {
          subscription.cancel();
          body.completeExceptionally(new ResponseTooLargeException());
          return;
        }
        byte[] part = new byte[buffer.remaining()];
        buffer.get(part);
        bytes.writeBytes(part);
      }
    }

    @Override
    public void onError(Throwable error) {
      body.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      body.complete(bytes.toByteArray());
    }
  }
}
