package com.fluxpay.common.contracts;
import com.fluxpay.common.enums.ScreeningVerdict;
import java.math.BigDecimal;
import java.util.UUID;
public interface ComplianceAssessor { ScreeningVerdict assess(UUID userId, BigDecimal amount, String currency); }
