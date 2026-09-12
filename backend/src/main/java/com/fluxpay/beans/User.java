package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

/** Persistent account record backed by the existing {@code users} table. */
@Entity
@Table(name = "users")
public class User {
  @Id
  @JdbcTypeCode(Types.BINARY)
  @Column(name = "id", nullable = false, columnDefinition = "RAW(16)")
  private UUID id;

  @Column(name = "email", nullable = false, length = 255)
  private String email;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Column(name = "role", nullable = false, length = 50)
  private String role;

  @Column(name = "full_name", nullable = false, length = 255)
  private String fullName;

  @JdbcTypeCode(Types.TIMESTAMP)
  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @JdbcTypeCode(Types.TIMESTAMP)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected User() {}

  public User(
      UUID id,
      String email,
      String passwordHash,
      String role,
      String fullName,
      Instant createdAt,
      Instant updatedAt) {
    this.id = id;
    this.email = email;
    this.passwordHash = passwordHash;
    this.role = role;
    this.fullName = fullName;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public String getRole() {
    return role;
  }

  public String getFullName() {
    return fullName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setFullName(String fullName) {
    this.fullName = fullName;
  }
}
