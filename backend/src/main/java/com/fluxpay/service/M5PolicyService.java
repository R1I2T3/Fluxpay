package com.fluxpay.service;
import com.fluxpay.beans.M5PolicyDocument;
import com.fluxpay.config.M5VectorSettings;
import com.fluxpay.config.M5EmbeddingAdapter;
import com.fluxpay.dto.M5PolicyDtos;
import com.fluxpay.repository.M5PolicyRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
@Service
public class M5PolicyService {
  public M5PolicyService(M5PolicyRepository repository,M5PolicyChunker chunker,M5EmbeddingAdapter provider,M5VectorSettings settings) {}
  public M5PolicyDocument create(M5PolicyDtos.Create request) { return null; }
  public M5PolicyDocument get(UUID id) { return null; }
  public M5PolicyDtos.Page list(int page,int size) { return null; }
  public M5PolicyDtos.Index index(UUID id) { return null; }
}
