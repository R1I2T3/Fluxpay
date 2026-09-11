package com.fluxpay.dto;
import com.fluxpay.beans.M5PolicyDocument;
import java.util.List;
import java.util.UUID;
public final class M5PolicyDtos {
  private M5PolicyDtos() {}
  public record Create(String title, String category, String content) {}
  public record Page(List<M5PolicyDocument> content, int page, int size, long totalElements, long totalPages) {}
  public record Index(UUID policyDocumentId, UUID generationId, String embeddingSpaceId,
      String chunkerVersion, int chunkCount, long version, boolean mock, boolean replayed) {}
  public record Match(UUID policyDocumentId, String title, int chunkNumber, String content, double distance) {}
}
