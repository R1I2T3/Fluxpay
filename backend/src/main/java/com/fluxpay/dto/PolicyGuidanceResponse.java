package com.fluxpay.dto;
import java.time.Instant;
import java.util.*;
public record PolicyGuidanceResponse(UUID id, UUID policyDocumentId, UUID complianceCaseId, String content, Instant createdAt, Instant updatedAt) {}
