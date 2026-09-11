package com.fluxpay.dto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
public final class M5CopilotDtos {
  private M5CopilotDtos() {}
  public record Ask(String question, UUID paymentId) {}
  public record Source(UUID policyDocumentId, String title, int chunkNumber, String excerpt) {}
  public record Answer(String answer, List<Source> sources, boolean mock, Map<String,Object> caseContext) {}
}
