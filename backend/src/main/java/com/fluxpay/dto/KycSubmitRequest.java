package com.fluxpay.dto;

import com.fluxpay.beans.KycDocumentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Metadata-only KYC submission input. */
public record KycSubmitRequest(
    @NotNull KycDocumentType docType,
    @NotBlank @Size(max = 64) String docNumber,
    @NotEmpty List<@Valid KycFileMeta> documents) {}
