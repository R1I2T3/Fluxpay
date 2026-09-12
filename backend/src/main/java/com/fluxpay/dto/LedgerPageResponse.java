package com.fluxpay.dto;

import java.util.List;

public record LedgerPageResponse(
    List<LedgerEntryResponse> entries, int page, int size, long totalElements, int totalPages) {}
