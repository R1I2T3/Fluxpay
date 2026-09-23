package com.fluxpay.dto;

import java.util.List;

public record TicketPageResponse(List<TicketResponse> items, int page, int size, long total) {}
