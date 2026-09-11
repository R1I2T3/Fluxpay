package com.fluxpay.repository;
import com.fluxpay.beans.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
public interface M5ScreeningStore {
  Optional<M5ScreeningCase> byAssessment(UUID id);
  Optional<M5ScreeningCase> byCase(UUID id, boolean lock);
  Optional<M5ScreeningHead> head(UUID paymentId, boolean lock);
  void createHead(UUID paymentId);
  void insertCase(M5ScreeningCase value);
  void updateCase(M5ScreeningCase value);
  void publishHead(M5ScreeningHead value);
  Optional<M5ReviewDecision> decisionForCase(UUID caseId);
  Optional<M5ReviewDecision> decision(UUID decisionId, boolean lock);
  void insertDecision(M5ReviewDecision value);
  void updateDelivery(M5ReviewDecision value);
  List<M5ReviewDecision> pending(Instant now,int limit);
  List<M5ScreeningCase> list(String status,String risk,Boolean reviewable,int page,int size);
  long count(String status,String risk,Boolean reviewable);
}
