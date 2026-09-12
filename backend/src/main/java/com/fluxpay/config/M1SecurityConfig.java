package com.fluxpay.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** Enables role checks on M1 controller methods while reusing the shared HTTP security setup. */
@Configuration
@EnableMethodSecurity
public class M1SecurityConfig {}
