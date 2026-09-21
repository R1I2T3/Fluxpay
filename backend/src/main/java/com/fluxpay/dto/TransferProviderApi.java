package com.fluxpay.dto;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.domain.RailType;
import java.time.Instant;
import java.util.List;

/** REST shapes for administrator-managed transfer providers. Entities are never serialized. */
public final class TransferProviderApi {
  private TransferProviderApi() {}

  public record ProviderListResponse(List<ProviderEntry> providers) {
    public ProviderListResponse {
      providers = providers == null ? List.of() : List.copyOf(providers);
    }
  }

  public record ProviderEntry(
      String id,
      String providerCode,
      String providerName,
      RailType railType,
      boolean active,
      boolean systemProtected,
      Instant archivedAt,
      long version) {}

  public record CreateProviderRequest(
      String providerCode, String providerName, String railType, Boolean active) {}

  public record UpdateProviderRequest(
      String providerName, String railType, Boolean active, Long version) {}

  public static ProviderEntry toEntry(TransferProvider provider) {
    return new ProviderEntry(
        provider.getId().toString(),
        provider.getProviderCode(),
        provider.getProviderName(),
        provider.getRailType(),
        provider.isActive(),
        provider.isSystemProtected(),
        provider.getArchivedAt(),
        provider.getVersion() == null ? 0L : provider.getVersion());
  }
}
