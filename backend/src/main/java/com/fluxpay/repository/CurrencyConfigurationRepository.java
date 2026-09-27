package com.fluxpay.repository;

import com.fluxpay.beans.CurrencyConfiguration;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface CurrencyConfigurationRepository extends Repository<CurrencyConfiguration, String> {
  Optional<CurrencyConfiguration> findById(String code);
}
