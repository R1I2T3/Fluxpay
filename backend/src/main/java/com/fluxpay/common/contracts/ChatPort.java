package com.fluxpay.common.contracts;

import com.fluxpay.dto.CopilotSource;
import java.util.List;

/** Generates a policy-grounded answer from retrieved, cited excerpts. */
public interface ChatPort {
  String answer(String question, List<CopilotSource> sources);
}
