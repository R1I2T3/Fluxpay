package com.fluxpay.dto;

import java.util.List;

public record CopilotAnswerResponse(String answer, List<CopilotSource> sources) {}
