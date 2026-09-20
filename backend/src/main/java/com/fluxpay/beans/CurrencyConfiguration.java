package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "currencies")
public class CurrencyConfiguration {
  @Id
  @Column(
      name = "code",
      columnDefinition = "CHAR(3)",
      length = 3,
      nullable = false,
      updatable = false)
  private String code;

  @Column(name = "scale", precision = 2, nullable = false, updatable = false)
  private Integer scale;

  protected CurrencyConfiguration() {}

  public String getCode() {
    return code;
  }

  public Integer getScale() {
    return scale;
  }
}
