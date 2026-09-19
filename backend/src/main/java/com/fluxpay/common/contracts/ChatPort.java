package com.fluxpay.common.contracts;

import com.fluxpay.dto.CopilotSource;
import java.util.List;
import java.util.function.Consumer;

/** Generates a policy-grounded answer from retrieved, cited excerpts. */
public interface ChatPort {
  String answer(String question, List<CopilotSource> sources);

  default void stream(String question, List<CopilotSource> sources, Consumer<String> onDelta) {
    onDelta.accept(answer(question, sources));
  }
}
