package com.fluxpay.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DevelopmentPayoutSimulationProperties.class)
public class DevelopmentPayoutSimulationConfiguration {}
