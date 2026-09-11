package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.CopilotAnswerResponse;
import com.fluxpay.dto.CopilotRequest;
import com.fluxpay.service.CopilotService;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("m5-legacy")
@RestController
@RequestMapping("/api/copilot")
public class CopilotController {

  private final CopilotService copilotService;

  public CopilotController(CopilotService copilotService) {
    this.copilotService = copilotService;
  }

  @PostMapping("/ask")
  public ApiResponse<CopilotAnswerResponse> ask(@Valid @RequestBody CopilotRequest request) {
    String cid = MDC.get("correlationId");
    return new ApiResponse<>(cid == null ? "none" : cid, copilotService.ask(request));
  }
}
