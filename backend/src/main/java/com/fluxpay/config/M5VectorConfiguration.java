package com.fluxpay.config;

import com.fluxpay.repository.M5PolicyRepository;
import com.fluxpay.service.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Profile("m5-backend") @Configuration(proxyBeanMethods=false)
@Import({M5PolicyRepository.class,M5PolicyService.class,M5CopilotService.class,M5CanonicalHashReconciler.class})
public class M5VectorConfiguration {
  @Bean M5VectorSettings m5VectorSettings(Environment environment) {return M5VectorSettings.from(environment);}
  @Bean M5EmbeddingAdapter m5EmbeddingAdapter(M5VectorSettings settings) {return new M5EmbeddingAdapter(settings);}
  @Bean M5PolicyChunker m5PolicyChunker(M5VectorSettings settings) {
    return new M5PolicyChunker(settings.minWords(),settings.maxWords(),settings.overlapWords());
  }
}
