package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.controller.ComplianceCaseController;
import com.fluxpay.controller.CopilotController;
import com.fluxpay.controller.PolicyController;
import com.fluxpay.repository.ComplianceLookupRepository;
import com.fluxpay.repository.PolicyVectorRepository;
import com.fluxpay.service.ApiEmbeddingProvider;
import com.fluxpay.service.ComplianceAssessmentService;
import com.fluxpay.service.ComplianceCaseService;
import com.fluxpay.service.CopilotService;
import com.fluxpay.service.MockEmbeddingProvider;
import com.fluxpay.service.OllamaAnswerGenerator;
import com.fluxpay.service.OllamaEmbeddingProvider;
import com.fluxpay.service.PolicyChunkService;
import com.fluxpay.service.PolicyDocumentService;
import com.fluxpay.service.PolicyIndexingService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

class M5LegacyIsolationUnitTest {
    @Test
    void newRuntimeRejectsExplicitActivationOfRetiredPrototype() {
        new ApplicationContextRunner().withUserConfiguration(M5LegacyGuard.class)
            .withPropertyValues("spring.profiles.active=m5-legacy")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void newRuntimeAllowsOrdinaryProfiles() {
        new ApplicationContextRunner().withUserConfiguration(M5LegacyGuard.class)
            .withPropertyValues("spring.profiles.active=m5-solo")
            .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void ordinaryComponentScanningCannotActivatePrototypeEndpointsOrProviders() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        for (Class<?> type : new Class<?>[] {
                ComplianceCaseController.class, PolicyController.class, CopilotController.class,
                ComplianceAssessmentService.class, ComplianceCaseService.class, CopilotService.class,
                PolicyChunkService.class, PolicyDocumentService.class, PolicyIndexingService.class,
                ApiEmbeddingProvider.class, MockEmbeddingProvider.class, OllamaEmbeddingProvider.class,
                OllamaAnswerGenerator.class, ComplianceLookupRepository.class, PolicyVectorRepository.class
        }) {
            scanner.addIncludeFilter(new AssignableTypeFilter(type));
        }
        assertThat(scanner.findCandidateComponents("com.fluxpay")).isEmpty();
    }
}
