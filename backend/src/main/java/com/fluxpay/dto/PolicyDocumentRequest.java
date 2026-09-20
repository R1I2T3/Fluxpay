package com.fluxpay.dto;

import com.fluxpay.common.enums.PolicyCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PolicyDocumentRequest(
    @NotBlank @Size(max = 200) String title,
    @NotNull PolicyCategory category,
    @NotBlank String content,
    Boolean clearExistingChunks) {

  public PolicyDocumentRequest {
    if (clearExistingChunks == null) {
      clearExistingChunks = true;
    }
  }

  public PolicyDocumentRequest(String title, PolicyCategory category, String content) {
    this(title, category, content, true);
  }
}
