package com.fluxpay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Maximum period in which an admin may release a payment using its reviewed quote. */
@ConfigurationProperties(prefix = "fluxpay.compliance")
public record ComplianceReviewWindowProperties(@DefaultValue("24") int reviewHoldHours) {
  public ComplianceReviewWindowProperties {
    if (reviewHoldHours < 1 || reviewHoldHours > 72) {
      throw new IllegalArgumentException("Compliance review hold must be between 1 and 72 hours");
    }
  }
}
