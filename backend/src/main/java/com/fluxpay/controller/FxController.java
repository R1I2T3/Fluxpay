package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.FxQuoteResponse;
import com.fluxpay.service.FxQuoteService;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fx")
public class FxController {
  private final FxQuoteService quotes;

  public FxController(FxQuoteService quotes) {
    this.quotes = quotes;
  }

  @GetMapping("/rate")
  public ApiResponse<FxQuoteResponse> rate(
      @RequestParam("from") String from, @RequestParam("to") String to) {
    return new ApiResponse<>(correlationId(), FxQuoteResponse.from(quotes.snapshot(from, to)));
  }

  private static String correlationId() {
    String value = MDC.get("correlationId");
    return value == null ? "none" : value;
  }
}
