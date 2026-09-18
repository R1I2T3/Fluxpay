package com.fluxpay.dto;

public record BankAccountResponse(
    String id, String bankName, String accountLast4, String currency, String status) {}
