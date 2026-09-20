package com.fluxpay.service;

import com.fluxpay.repository.CurrencyConfigurationRepository;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Reads currency business precision from the canonical currencies table. */
@Service
public class CurrencyScaleService {
  private final CurrencyConfigurationRepository currencies;

  public CurrencyScaleService(CurrencyConfigurationRepository currencies) {
    this.currencies = currencies;
  }

  public int scale(String currency) {
    String code = currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    Integer scale =
        currencies
            .findById(code)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported currency: " + code))
            .getScale();
    if (scale == null || scale < 0 || scale > 4) {
      throw new IllegalStateException("Invalid business scale configured for " + code);
    }
    return scale;
  }
}
