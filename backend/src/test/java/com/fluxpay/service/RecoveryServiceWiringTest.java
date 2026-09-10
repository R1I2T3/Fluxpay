package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RecoveryServiceWiringTest {

  @Test
  void usesTheFullDependencyConstructorForSpringInjection() {
    assertThat(Arrays.stream(RecoveryService.class.getConstructors()))
        .filteredOn(constructor -> constructor.getParameterCount() == 8)
        .singleElement()
        .satisfies(constructor -> assertThat(constructor.isAnnotationPresent(Autowired.class)).isTrue());
  }
}
