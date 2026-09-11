package com.fluxpay.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Prevents accidentally serving the retired prototype API in the new runtime. */
@Configuration(proxyBeanMethods = false)
public class M5LegacyGuard {
    @Bean
    static BeanFactoryPostProcessor m5RejectLegacyRuntime(Environment environment) {
        return beanFactory -> {
            if (environment.acceptsProfiles(Profiles.of("m5-legacy"))) {
                throw new IllegalStateException(
                    "The retired m5-legacy runtime cannot be enabled with the authoritative M5 backend");
            }
        };
    }
}
