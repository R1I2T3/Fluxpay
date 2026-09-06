package com.fluxpay.common.contracts;
import java.util.UUID;
public interface KycGate { boolean isVerified(UUID userId); }
