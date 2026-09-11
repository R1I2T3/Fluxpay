package com.fluxpay.dto;

import java.util.List;

public record M5RuleResult(String risk, String screeningVerdict, String status,
    String suggestedAction, List<M5RiskReason> reasons, String ruleVersion, String ruleConfigHash) {
  public M5RuleResult { reasons = List.copyOf(reasons); }
}
