package com.fluxpay.dto;

import java.util.List;

public record HoldPreviewResponse(
    boolean likely, String risk, List<String> reasons, List<String> reasonMessages) {}
