package com.fluxpay.dto;
import java.util.List;
public record M5CasePage(List<M5CaseResponse> items, int page, int size, long totalElements) {}
