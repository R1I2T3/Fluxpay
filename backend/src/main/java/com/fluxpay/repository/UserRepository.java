package com.fluxpay.repository;

import com.fluxpay.beans.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
  @Query("select u from User u where lower(trim(u.email)) = :canonicalEmail")
  Optional<User> findByCanonicalEmail(@Param("canonicalEmail") String canonicalEmail);

  @Query("select count(u) > 0 from User u where lower(trim(u.email)) = :canonicalEmail")
  boolean existsByCanonicalEmail(@Param("canonicalEmail") String canonicalEmail);
}
