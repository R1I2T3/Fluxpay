package com.fluxpay.m5.domain;

import com.fluxpay.m5.api.CopilotSource;
import java.util.List;

/** Generates a policy-grounded answer from retrieved, cited excerpts. */
public interface M5ChatPort {
  String answer(String question, List<CopilotSource> sources);
}
