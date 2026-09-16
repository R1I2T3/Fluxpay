package com.fluxpay.m5.api;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.m5.application.M5CopilotService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** HTTP entry point for cited Compliance Copilot answers. */
@RestController
@RequestMapping("/api/copilot")
public class M5CopilotController {
  private final M5CopilotService copilotService;

  public M5CopilotController(M5CopilotService copilotService) {
    this.copilotService = copilotService;
  }

  @PostMapping("/ask")
  public ApiResponse<CopilotAnswerResponse> ask(@Valid @RequestBody CopilotRequest request) {
    String correlationId = MDC.get("correlationId");
    return new ApiResponse<>(
        correlationId == null ? "none" : correlationId, copilotService.ask(request));
  }
}
