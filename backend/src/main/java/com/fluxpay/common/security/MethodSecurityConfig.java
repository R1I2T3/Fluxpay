package com.fluxpay.common.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Enables role checks on controller methods while reusing the shared HTTP security setup. */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {}
