package com.fluxpay.dto;
import com.fluxpay.beans.RecipientStatus; import jakarta.validation.constraints.*;
public record RecipientRequest(@NotBlank @Size(max=150) String name, @NotBlank @Size(max=150) String account, @NotBlank @Size(max=150) String bankName, @Pattern(regexp="[A-Za-z]{2}") String country, @Pattern(regexp="USD|EUR|INR") String currency, RecipientStatus status, @PositiveOrZero Long expectedVersion) {}
