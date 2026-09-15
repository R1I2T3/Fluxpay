package com.fluxpay.repository;
import com.fluxpay.beans.M3ReviewDecision;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface M3ReviewDecisionRepository extends JpaRepository<M3ReviewDecision,UUID> {
  Optional<M3ReviewDecision> findByReviewReference(String reference);
}
