package com.fluxpay.service;

import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.WalletOperationRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.support.PersistenceExceptionTranslationInterceptor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Real JPA transactions in an isolated in-memory database; Oracle DDL is verified in Task 11. */
class OperationDatabase implements AutoCloseable {
  final LocalContainerEntityManagerFactoryBean factory =
      new LocalContainerEntityManagerFactoryBean();
  final JpaTransactionManager transactions;
  final PaymentOperationRepository payments;
  final WalletOperationRepository wallets;

  OperationDatabase() {
    factory.setDataSource(
        new DriverManagerDataSource(
            "jdbc:h2:mem:operations-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setManagedTypes(
        PersistenceManagedTypes.of(
            PaymentOperation.class.getName(), WalletOperation.class.getName()));
    factory.setJpaPropertyMap(
        Map.of(
            "hibernate.hbm2ddl.auto",
            "create-drop",
            "hibernate.dialect",
            "org.hibernate.dialect.H2Dialect"));
    factory.afterPropertiesSet();
    transactions = new JpaTransactionManager(factory.getObject());
    var repositories =
        new JpaRepositoryFactory(
            SharedEntityManagerCreator.createSharedEntityManager(factory.getObject()));
    payments = translated(repositories.getRepository(PaymentOperationRepository.class));
    wallets = translated(repositories.getRepository(WalletOperationRepository.class));
  }

  @SuppressWarnings("unchecked")
  private <T> T translated(T repository) {
    var proxy = new ProxyFactory(repository);
    proxy.addAdvice(new PersistenceExceptionTranslationInterceptor(factory));
    return (T) proxy.getProxy();
  }

  @SuppressWarnings("unchecked")
  <T> T transactional(T service) {
    var proxy = new ProxyFactory(service);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (T) proxy.getProxy();
  }

  @Override
  public void close() {
    factory.destroy();
  }
}
