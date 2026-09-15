package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.M5CopilotDtos;
import com.fluxpay.service.M5CopilotService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Profile("m5-backend")
@RestController @RequestMapping("/api/copilot")
public class M5CopilotController {
  private final M5CopilotService copilot;
  public M5CopilotController(M5CopilotService copilot) {this.copilot=copilot;}
  @PostMapping("/ask") public ApiResponse<M5CopilotDtos.Answer> ask(
      @RequestBody M5CopilotDtos.Ask request,@AuthenticationPrincipal CurrentUser actor) {
    M5PolicyController.requireAdmin(actor);
    return M5PolicyController.wrap(copilot.ask(request,actor));
  }
}
