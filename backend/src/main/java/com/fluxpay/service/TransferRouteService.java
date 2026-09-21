package com.fluxpay.service;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.dto.DeletionResult;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferProviderRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator lifecycle for transfer routes: CRUD, optimistic locking, provider/destination
 * binding immutability after first use, and delete/archive decisions.
 */
@Service
public class TransferRouteService {
  /** Route identity plus eligibility corridor, commercials, limits, and active status. */
  public record CreateRoute(
      UUID providerId,
      String routeCode,
      String name,
      DestinationType destinationType,
      String destinationCountry,
      String payoutCurrency,
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal configuredSuccessRate,
      BigDecimal minimumRecipientAmount,
      BigDecimal maximumRecipientAmount,
      boolean active) {}

  /** Mutable route fields; the code is immutable and the provider/destination bind after use. */
  public record UpdateRoute(
      UUID providerId,
      String name,
      DestinationType destinationType,
      String destinationCountry,
      String payoutCurrency,
      BigDecimal baseFee,
      BigDecimal fxSpreadPercentage,
      int estimatedMinutes,
      BigDecimal configuredSuccessRate,
      BigDecimal minimumRecipientAmount,
      BigDecimal maximumRecipientAmount,
      boolean active,
      Long expectedVersion) {}

  private final TransferRouteRepository routes;
  private final TransferProviderRepository providers;
  private final RoutingUsageService usage;
  private final RailRegistry rails;
  private final Clock clock;

  public TransferRouteService(
      TransferRouteRepository routes,
      TransferProviderRepository providers,
      RoutingUsageService usage,
      RailRegistry rails,
      Clock clock) {
    this.routes = routes;
    this.providers = providers;
    this.usage = usage;
    this.rails = rails;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<TransferRoute> list() {
    return routes.findAllByOrderByRouteCodeAsc();
  }

  @Transactional(readOnly = true)
  public TransferRoute get(UUID id) {
    return find(id);
  }

  @Transactional
  public TransferRoute create(CreateRoute command) {
    if (command.providerId() == null) {
      throw invalid("providerId must not be null");
    }
    TransferProvider provider = findProvider(command.providerId(), command.active());
    String code;
    try {
      code = TransferProvider.normalizeCode(command.routeCode());
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    }
    if (routes.findByRouteCode(code).isPresent()) {
      throw conflict("ROUTE_CODE_CONFLICT", "A route with this code already exists.");
    }
    if (command.destinationType() == null) {
      throw invalid("destinationType must not be null");
    }
    if (command.active() && !provider.active()) {
      throw invalid("The route cannot be active while its provider is inactive.");
    }
    RoutingCompatibility.requireCompatible(
        rails, provider.railType(), command.destinationType(), command.active());
    TransferRoute route;
    try {
      route =
          TransferRoute.create(
              UUID.randomUUID(),
              provider,
              code,
              command.name(),
              command.destinationType(),
              command.destinationCountry(),
              command.payoutCurrency(),
              command.baseFee(),
              command.fxSpreadPercentage(),
              command.estimatedMinutes(),
              command.configuredSuccessRate(),
              command.minimumRecipientAmount(),
              command.maximumRecipientAmount(),
              command.active(),
              false,
              Instant.now(clock));
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    }
    try {
      return routes.saveAndFlush(route);
    } catch (DataIntegrityViolationException e) {
      throw conflict("ROUTE_CODE_CONFLICT", "A route with this code already exists.");
    }
  }

  @Transactional
  public TransferRoute update(UUID id, UpdateRoute command) {
    TransferRoute route = find(id);
    requireVersion(route, command.expectedVersion());
    if (command.providerId() == null) {
      throw invalid("providerId must not be null");
    }
    if (command.destinationType() == null) {
      throw invalid("destinationType must not be null");
    }
    boolean bindingChanged =
        !command.providerId().equals(route.provider().id())
            || !command.destinationType().equals(route.destinationType());
    if (bindingChanged && usage.routeUsed(id)) {
      throw conflict(
          "ROUTING_BINDING_IMMUTABLE",
          "The route provider and destination cannot change after the route has been used.");
    }
    TransferProvider provider = findProvider(command.providerId(), command.active());
    if (command.active() && !provider.active()) {
      throw invalid("The route cannot be active while its provider is inactive.");
    }
    RoutingCompatibility.requireCompatible(
        rails, provider.railType(), command.destinationType(), command.active());
    try {
      route.update(
          provider,
          command.name(),
          command.destinationType(),
          command.destinationCountry(),
          command.payoutCurrency(),
          command.baseFee(),
          command.fxSpreadPercentage(),
          command.estimatedMinutes(),
          command.configuredSuccessRate(),
          command.minimumRecipientAmount(),
          command.maximumRecipientAmount(),
          command.active(),
          Instant.now(clock));
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage());
    }
    routes.flush();
    return route;
  }

  @Transactional
  public DeletionResult delete(UUID id, Long expectedVersion) {
    TransferRoute route = find(id);
    requireVersion(route, expectedVersion);
    if (usage.routeUsed(id) || route.systemProtected()) {
      route.archive(Instant.now(clock));
      routes.flush();
      return new DeletionResult(DeletionResult.Disposition.ARCHIVED, id);
    }
    routes.delete(route);
    return new DeletionResult(DeletionResult.Disposition.DELETED, id);
  }

  private TransferRoute find(UUID id) {
    return routes
        .findById(id)
        .orElseThrow(() -> notFound("ROUTE_NOT_FOUND", "Transfer route not found."));
  }

  private TransferProvider findProvider(UUID providerId, boolean activeRoute) {
    return (activeRoute ? providers.findByIdForUpdate(providerId) : providers.findById(providerId))
        .orElseThrow(() -> notFound("PROVIDER_NOT_FOUND", "Transfer provider not found."));
  }

  private void requireVersion(TransferRoute route, Long expectedVersion) {
    if (expectedVersion == null || !expectedVersion.equals(route.version())) {
      throw conflict("STALE_ROUTE", "The route was changed. Refresh and try again.");
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
