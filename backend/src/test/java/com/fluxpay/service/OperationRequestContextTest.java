package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.context.request.ServletWebRequest;

class OperationRequestContextTest {
  @Test
  void runtimeRequestBoundaryDoesNotReuseTheFailedPersistenceContext() throws Exception {
    try (var db = new OperationDatabase()) {
      var runtime =
          new YamlPropertySourceLoader().load("runtime", new ClassPathResource("application.yml"));
      new WebApplicationContextRunner()
          .withInitializer(
              context ->
                  runtime.forEach(
                      source -> context.getEnvironment().getPropertySources().addLast(source)))
          .withConfiguration(AutoConfigurations.of(HibernateJpaAutoConfiguration.class))
          .withBean(DataSource.class, () -> db.factory.getDataSource())
          .withBean(EntityManagerFactory.class, () -> db.factory.getObject())
          .run(
              context -> {
                assertThat(context).hasNotFailed();
                var request = new ServletWebRequest(new MockHttpServletRequest());
                var osiv =
                    context
                        .getBeansOfType(OpenEntityManagerInViewInterceptor.class)
                        .values()
                        .stream()
                        .findFirst();
                osiv.ifPresent(interceptor -> interceptor.preHandle(request));
                var losing = new AtomicReference<EntityManager>();
                var reread = new AtomicReference<EntityManager>();
                var firstRead = new AtomicBoolean(true);
                var worker = Executors.newSingleThreadExecutor();
                try {
                  var proxy = new ProxyFactory(db.payments);
                  proxy.addAdvice(
                      (MethodInterceptor)
                          invocation -> {
                            var em =
                                EntityManagerFactoryUtils.getTransactionalEntityManager(
                                    db.factory.getObject());
                            if (invocation.getMethod().getName().startsWith("findByUserId")
                                && losing.get() != null) reread.set(em);
                            Object result;
                            try {
                              result = invocation.proceed();
                            } catch (DataIntegrityViolationException race) {
                              losing.set(em);
                              throw race;
                            }
                            if (invocation.getMethod().getName().startsWith("findByUserId")
                                && firstRead.getAndSet(false)) {
                              worker
                                  .submit(
                                      () ->
                                          PaymentOperationServiceTest.service(db)
                                              .reserve(
                                                  PaymentOperationServiceTest.USER,
                                                  "request-race",
                                                  "SUBMIT",
                                                  PaymentOperationServiceTest.PAYMENT,
                                                  java.util.Map.of(),
                                                  PaymentOperationServiceTest.EventResponse.class))
                                  .get(5, TimeUnit.SECONDS);
                            }
                            return result;
                          });
                  var operations =
                      new PaymentOperationService(
                          (com.fluxpay.repository.PaymentOperationRepository) proxy.getProxy(),
                          new com.fasterxml.jackson.databind.ObjectMapper(),
                          java.time.Clock.systemUTC(),
                          db.transactions);
                  assertThatThrownBy(
                          () ->
                              operations.reserve(
                                  PaymentOperationServiceTest.USER,
                                  "request-race",
                                  "SUBMIT",
                                  PaymentOperationServiceTest.PAYMENT,
                                  java.util.Map.of(),
                                  PaymentOperationServiceTest.EventResponse.class))
                      .isInstanceOfSatisfying(
                          com.fluxpay.exception.BusinessException.class,
                          e -> assertThat(e.code()).isEqualTo("OPERATION_IN_PROGRESS"));
                  assertThat(losing.get()).isNotNull();
                  assertThat(reread.get()).isNotNull().isNotSameAs(losing.get());
                  assertThat(losing.get().isOpen()).isFalse();
                } finally {
                  worker.shutdownNow();
                  osiv.ifPresent(interceptor -> interceptor.afterCompletion(request, null));
                }
              });
    }
  }
}
