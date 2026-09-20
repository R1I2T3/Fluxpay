package com.fluxpay.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the review-window setting independently of the configured screening implementation. */
@Configuration
@EnableConfigurationProperties(ComplianceReviewWindowProperties.class)
public class ComplianceReviewWindowConfiguration {}
