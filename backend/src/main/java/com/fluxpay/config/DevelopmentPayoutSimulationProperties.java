package com.fluxpay.config;

import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "fluxpay.development")
public record DevelopmentPayoutSimulationProperties(
    @DefaultValue("false") boolean simulatedPayoutsEnabled,
    String retrySuccessRouteCode,
    @DefaultValue("0") int retrySuccessFailureAttempts,
    String refundRouteCode,
    @DefaultValue("0") int refundFailureAttempts,
    @DefaultValue("120") int recoveryDelaySeconds) {

  public DevelopmentPayoutSimulationProperties {
    retrySuccessRouteCode = normalizedRoute(retrySuccessRouteCode);
    refundRouteCode = normalizedRoute(refundRouteCode);
    if (retrySuccessFailureAttempts < 0 || refundFailureAttempts < 0)
      throw new IllegalArgumentException("failure attempts must not be negative");
    if (recoveryDelaySeconds < 1)
      throw new IllegalArgumentException("recovery delay must be at least one second");
    if (!simulatedPayoutsEnabled
        && (retrySuccessRouteCode != null
            || refundRouteCode != null
            || recoveryDelaySeconds != 120))
      throw new IllegalArgumentException(
          "Development payout policies require simulated-payouts-enabled=true");
    if (retrySuccessRouteCode != null && retrySuccessRouteCode.equals(refundRouteCode))
      throw new IllegalArgumentException("Demo failure route codes must be distinct");
  }

  public Optional<RouteFailurePolicy> failurePolicyFor(String routeCode) {
    if (routeCode == null) return Optional.empty();
    if (retrySuccessRouteCode != null
        && retrySuccessRouteCode.equals(routeCode)
        && retrySuccessFailureAttempts > 0)
      return Optional.of(new RouteFailurePolicy(routeCode, retrySuccessFailureAttempts));
    if (refundRouteCode != null && refundRouteCode.equals(routeCode) && refundFailureAttempts > 0)
      return Optional.of(new RouteFailurePolicy(routeCode, refundFailureAttempts));
    return Optional.empty();
  }

  private static String normalizedRoute(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim();
  }

  public record RouteFailurePolicy(String routeCode, int failureAttempts) {}
}
