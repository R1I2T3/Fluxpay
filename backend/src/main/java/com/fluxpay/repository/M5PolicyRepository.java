package com.fluxpay.repository;
import com.fluxpay.beans.M5PolicyDocument;
import com.fluxpay.beans.M5PolicyChunk;
import com.fluxpay.dto.M5PolicyDtos;
import com.fluxpay.service.M5WorkDeadline;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
@Repository
public class M5PolicyRepository {
  public M5PolicyRepository(DataSource source) {}
  public Optional<M5PolicyDocument> find(UUID id, M5WorkDeadline deadline) { return Optional.empty(); }
  public Optional<M5PolicyDocument> findByHash(String hash, M5WorkDeadline deadline) { return Optional.empty(); }
  public M5PolicyDocument create(M5PolicyDocument doc, M5WorkDeadline deadline) { return doc; }
  public M5PolicyDtos.Page list(int page,int size,M5WorkDeadline deadline) { return null; }
  public M5PolicyDocument publish(M5PolicyDocument captured,List<M5PolicyChunk> chunks,String space,String chunker,M5WorkDeadline deadline) { return captured; }
  public List<M5PolicyDtos.Match> search(float[] vector,String space,int topK,double maxDistance,M5WorkDeadline deadline) { return List.of(); }
}
