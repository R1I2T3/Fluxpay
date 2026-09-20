package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.CopilotAnswerResponse;
import com.fluxpay.dto.CopilotRequest;
import com.fluxpay.service.CopilotService;
import jakarta.validation.Valid;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** HTTP entry point for cited Compliance Copilot answers. */
@RestController
@RequestMapping("/api/copilot")
public class CopilotController {
  private final CopilotService copilotService;

  public CopilotController(CopilotService copilotService) {
    this.copilotService = copilotService;
  }

  @PostMapping("/ask")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<CopilotAnswerResponse> ask(@Valid @RequestBody CopilotRequest request) {
    String correlationId = MDC.get("correlationId");
    return new ApiResponse<>(
        correlationId == null ? "none" : correlationId, copilotService.ask(request));
  }

  @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @PreAuthorize("hasRole('ADMIN')")
  public SseEmitter stream(@Valid @RequestBody CopilotRequest request) {
    SseEmitter emitter = new SseEmitter(90_000L);
    CompletableFuture.runAsync(
        () -> {
          try {
            copilotService.stream(
                request,
                delta -> {
                  try {
                    emitter.send(SseEmitter.event().data(Map.of("delta", delta)));
                  } catch (java.io.IOException exception) {
                    throw new UncheckedIOException(exception);
                  }
                });
            emitter.send(SseEmitter.event().name("done").data(Map.of("done", true)));
            emitter.complete();
          } catch (Exception exception) {
            emitter.completeWithError(exception);
          }
        });
    return emitter;
  }
}
