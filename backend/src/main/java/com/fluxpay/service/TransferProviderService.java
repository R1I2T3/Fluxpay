package com.fluxpay.service;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.DeletionResult;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferProviderRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator lifecycle for transfer providers: CRUD, optimistic locking, rail-binding
 * immutability after first use, and delete/archive decisions.
 */
@Service
public class TransferProviderService {
  /** Provider identity is set once; administrators never configure endpoints or secrets. */
  public record CreateProvider(
      String providerCode, String providerName, RailType railType, boolean active) {}

  public record UpdateProvider(
      String providerName, RailType railType, boolean active, Long expectedVersion) {}

  private final TransferProviderRepository providers;
  private final TransferRouteRepository routes;
  private final RoutingUsageService usage;
  private final RailRegistry rails;
  private final Clock clock;

  public TransferProviderService(
      TransferProviderRepository providers,
      TransferRouteRepository routes,
      RoutingUsageService usage,
      RailRegistry rails,
      Clock clock) {
    this.providers = providers;
    this.routes = routes;
    this.usage = usage;
    this.rails = rails;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<TransferProvider> list() {
    return providers.findAllByOrderByProviderCodeAsc();
  }

  @Transactional(readOnly = true)
  public TransferProvider get(UUID id) {
    return find(id);
  }

  @Transactional
  public TransferProvider create(CreateProvider command) {
    if (command.railType() == null) {
      throw invalid("railType must not be null");
    }
    String code;
    try {
      code = TransferProvider.normalizeCode(command.providerCode());
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    }
    if (providers.findByProviderCode(code).isPresent()) {
      throw conflict("PROVIDER_CODE_CONFLICT", "A provider with this code already exists.");
    }
    Instant now = Instant.now(clock);
    TransferProvider provider;
    try {
      provider =
          TransferProvider.create(
              UUID.randomUUID(),
              code,
              command.providerName(),
              command.railType(),
              command.active(),
              false,
              now);
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    }
    try {
      return providers.saveAndFlush(provider);
    } catch (DataIntegrityViolationException e) {
      throw conflict("PROVIDER_CODE_CONFLICT", "A provider with this code already exists.");
    }
  }

  @Transactional
  public TransferProvider update(UUID id, UpdateProvider command) {
    TransferProvider provider = find(id);
    requireVersion(provider, command.expectedVersion());
    if (command.railType() == null) {
      throw invalid("railType must not be null");
    }
    if (!command.railType().equals(provider.railType()) && usage.providerUsed(id)) {
      throw conflict(
          "ROUTING_BINDING_IMMUTABLE",
          "The provider rail cannot change after its routes have been used.");
    }
    try {
      provider.update(
          command.providerName(), command.railType(), command.active(), Instant.now(clock));
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    }
    requireCompatibleWithChildren(provider);
    providers.flush();
    return provider;
  }

  @Transactional
  public DeletionResult delete(UUID id, Long expectedVersion) {
    TransferProvider provider = find(id);
    requireVersion(provider, expectedVersion);
    List<TransferRoute> children = routes.findByProviderIdOrderByRouteCodeAsc(id);
    if (children.stream().anyMatch(child -> child.archivedAt() == null)) {
      throw conflict(
          "PROVIDER_HAS_ROUTES", "Remove or archive the provider routes before deletion.");
    }
    if (usage.providerUsed(id) || provider.systemProtected() || !children.isEmpty()) {
      // Archived children keep their provider_id foreign key, so the provider archives
      // to preserve catalogue history instead of violating the constraint.
      provider.archive(Instant.now(clock));
      providers.flush();
      return new DeletionResult(DeletionResult.Disposition.ARCHIVED, id);
    }
    providers.delete(provider);
    return new DeletionResult(DeletionResult.Disposition.DELETED, id);
  }

  private TransferProvider find(UUID id) {
    return providers
        .findById(id)
        .orElseThrow(() -> notFound("PROVIDER_NOT_FOUND", "Transfer provider not found."));
  }

  private void requireVersion(TransferProvider provider, Long expectedVersion) {
    if (expectedVersion == null || !expectedVersion.equals(provider.version())) {
      throw conflict("STALE_PROVIDER", "The provider was changed. Refresh and try again.");
    }
  }

  private void requireCompatibleWithChildren(TransferProvider provider) {
    for (TransferRoute child : routes.findByProviderIdOrderByRouteCodeAsc(provider.id())) {
      RoutingCompatibility.requireCompatibleIfInstalled(
          rails, provider.railType(), child.destinationType());
    }
  }

  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER_ROUTE", message);
  }

  private BusinessException conflict(String code, String message) {
    return new BusinessException(HttpStatus.CONFLICT, code, message);
  }

  private BusinessException notFound(String code, String message) {
    return new BusinessException(HttpStatus.NOT_FOUND, code, message);
  }
}
