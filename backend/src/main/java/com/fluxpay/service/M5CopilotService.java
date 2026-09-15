package com.fluxpay.service;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.config.M5VectorSettings;
import com.fluxpay.config.M5EmbeddingAdapter;
import com.fluxpay.dto.M5CopilotDtos;
import com.fluxpay.repository.M5PolicyRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
@Service
public class M5CopilotService {
  private static final String NO_ANSWER = "No grounded answer found in current policies.";
  private final M5PolicyRepository repository;
  private final M5EmbeddingAdapter provider;
  private final M5VectorSettings settings;
  private final M5CaseContextReader cases;

  public M5CopilotService(M5PolicyRepository repository,M5EmbeddingAdapter provider,
      M5VectorSettings settings,M5CaseContextReader cases) {
    this.repository = repository;
    this.provider = provider;
    this.settings = settings;
    this.cases = cases;
  }

  public M5CopilotDtos.Answer ask(M5CopilotDtos.Ask request,CurrentUser actor) {
    if (request == null || request.question() == null || request.question().isBlank()
        || request.question().codePointCount(0, request.question().length()) > 1000) {
      throw new M5ApiException(400, "VALIDATION", "Question must contain 1 to 1000 code points");
    }
    M5WorkDeadline deadline = new M5WorkDeadline(Duration.ofSeconds(5), "COPILOT_TIMEOUT");
    Map<String, Object> context = request.paymentId() == null
        ? Map.of() : cases.context(request.paymentId(), actor, deadline);
    deadline.check();
    float[] vector = provider.query(request.question(), deadline);
    List<com.fluxpay.dto.M5PolicyDtos.Match> matches = repository.search(vector,
        settings.spaceId(), settings.topK(), settings.maxDistance(), deadline);
    deadline.check();
    if (matches.isEmpty()) {
      return new M5CopilotDtos.Answer(NO_ANSWER, List.of(), mock(), context);
    }
    List<M5CopilotDtos.Source> sources = new ArrayList<>();
    for (var match : matches) {
      String content = match.content() == null ? "" : match.content();
      int end = Math.min(300, content.length());
      if (end < content.length() && end > 0 && Character.isHighSurrogate(content.charAt(end - 1))) {
        end--;
      }
      String excerpt = content.substring(0, end);
      sources.add(new M5CopilotDtos.Source(match.policyDocumentId(), match.title(),
          match.chunkNumber(), excerpt));
    }
    return new M5CopilotDtos.Answer(sources.get(0).excerpt(), List.copyOf(sources), mock(), context);
  }

  private boolean mock() {
    return settings.mode().equals("mock");
  }
}
