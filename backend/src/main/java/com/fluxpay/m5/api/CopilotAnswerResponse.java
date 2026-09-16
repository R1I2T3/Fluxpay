package com.fluxpay.m5.api;

import java.util.List;

/** An extractive answer and the policy passages cited as its evidence. */
public record CopilotAnswerResponse(String answer, List<CopilotSource> sources) {}
