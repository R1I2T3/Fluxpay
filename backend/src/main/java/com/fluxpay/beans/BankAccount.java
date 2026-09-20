package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "bank_accounts")
public class BankAccount {
  @Id
  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID id;

  @JdbcTypeCode(SqlTypes.BINARY)
  @Column(name = "user_id", columnDefinition = "RAW(16)", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "bank_name", length = 80, nullable = false)
  private String bankName;

  @Column(name = "account_last4", columnDefinition = "CHAR(4)", length = 4, nullable = false)
  private String accountLast4;

  @Column(name = "currency", columnDefinition = "CHAR(3)", length = 3, nullable = false)
  private String currency;

  @Column(name = "status", length = 16, nullable = false)
  private String status;

  protected BankAccount() {}

  public BankAccount(
      UUID userId, String bankName, String accountLast4, String currency, String status) {
    this.id = UUID.randomUUID();
    this.userId = Objects.requireNonNull(userId);
    this.bankName = Objects.requireNonNull(bankName);
    this.accountLast4 = Objects.requireNonNull(accountLast4);
    this.currency = Objects.requireNonNull(currency);
    this.status = Objects.requireNonNull(status);
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getBankName() {
    return bankName;
  }

  public String getAccountLast4() {
    return accountLast4;
  }

  public String getCurrency() {
    return currency;
  }

  public String getStatus() {
    return status;
  }
}
