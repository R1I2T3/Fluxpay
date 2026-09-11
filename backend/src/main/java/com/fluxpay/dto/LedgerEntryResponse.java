package com.fluxpay.dto;

public record LedgerEntryResponse(
    String entryId,
    String entryType,
    String amount,
    String currency,
    String journalReference,
    String narration,
    String createdAt) {}
