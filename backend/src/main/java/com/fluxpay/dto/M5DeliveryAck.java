package com.fluxpay.dto;

import java.util.UUID;

public record M5DeliveryAck(UUID decisionId, String status) {}
