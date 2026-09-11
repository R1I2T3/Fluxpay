package com.fluxpay.service;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.config.M5VectorSettings;
import com.fluxpay.config.M5EmbeddingAdapter;
import com.fluxpay.dto.M5CopilotDtos;
import com.fluxpay.repository.M5PolicyRepository;
import org.springframework.stereotype.Service;
@Service
public class M5CopilotService {
  public M5CopilotService(M5PolicyRepository repository,M5EmbeddingAdapter provider,M5VectorSettings settings,M5CaseContextReader cases) {}
  public M5CopilotDtos.Answer ask(M5CopilotDtos.Ask request,CurrentUser actor) { return null; }
}
