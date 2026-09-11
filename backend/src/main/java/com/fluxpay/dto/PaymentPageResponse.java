package com.fluxpay.dto; import java.util.List; public record PaymentPageResponse(List<PaymentResponse> items,int page,int size,long total) {}
