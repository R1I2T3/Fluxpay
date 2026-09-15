package com.fluxpay.service;
import com.fluxpay.beans.M5PolicyDocument;
import com.fluxpay.config.M5VectorSettings;
import com.fluxpay.config.M5EmbeddingAdapter;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.beans.M5PolicyChunk;
import com.fluxpay.dto.M5PolicyDtos;
import com.fluxpay.repository.M5PolicyRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
@Service
public class M5PolicyService {
  private static final Set<String> CATEGORIES=Set.of("KYC","AML","PAYMENT_REVIEW","COUNTRY_RULE","SUPPORT");
  private final M5PolicyRepository repository;
  private final M5PolicyChunker chunker;
  private final M5EmbeddingAdapter provider;
  private final M5VectorSettings settings;
  public M5PolicyService(M5PolicyRepository repository,M5PolicyChunker chunker,M5EmbeddingAdapter provider,M5VectorSettings settings) {
    this.repository=repository;this.chunker=chunker;this.provider=provider;this.settings=settings;
  }
  public M5PolicyDocument create(M5PolicyDtos.Create request) {
    if(request==null || request.title()==null || request.content()==null || !CATEGORIES.contains(request.category()==null?"":request.category()))
      throw validation("Title, supported category and content are required");
    String title=request.title().strip(), content=M5PolicyChunker.normalize(request.content());
    if(title.isBlank() || title.codePointCount(0,title.length())>200) throw validation("Title must contain 1 to 200 characters");
    if(content.isBlank() || content.getBytes(StandardCharsets.UTF_8).length>65536 || content.split("(?U)\\s+").length>5000)
      throw validation("Content must be nonempty and at most 64 KiB UTF-8 and 5000 words");
    var deadline=deadline();repository.requireReady(deadline);
    String hash=M5PolicyChunker.hash(content);
    repository.findByHash(hash,deadline).ifPresent(existing->{throw new M5ApiException(409,"DUPLICATE_POLICY","Normalized policy content already exists",Map.of("existingPolicyDocumentId",existing.id().toString()));});
    return repository.create(new M5PolicyDocument(UUID.randomUUID(),title,request.category(),content,hash,Instant.now(),0,"UNINDEXED",null,null,null,0),deadline);
  }
  public M5PolicyDocument get(UUID id) {
    var deadline=deadline();repository.requireReady(deadline);return get(id,deadline);
  }
  public M5PolicyDtos.Page list(int page,int size) {
    if(page<0 || size<1 || size>100) throw validation("Page must be nonnegative and size must be 1 to 100");
    var deadline=deadline();repository.requireReady(deadline);return repository.list(page,size,deadline);
  }
  public M5PolicyDtos.Index index(UUID id) {
    var deadline=new M5WorkDeadline(Duration.ofSeconds(30),"INDEX_TIMEOUT");
    repository.requireReady(deadline);
    M5PolicyDocument captured=get(id,deadline);
    String space=settings.spaceId(), chunkerVersion="m5-sentence-v1:"+settings.minWords()+":"+settings.maxWords()+":"+settings.overlapWords();
    if(captured.activeGenerationId()!=null && "INDEXED".equals(captured.indexState()) && space.equals(captured.embeddingSpaceId()) && chunkerVersion.equals(captured.chunkerVersion()))
      return indexed(captured,true);
    var texts=chunker.chunks(captured.content());
    if(texts.isEmpty() || texts.size()>16) throw validation("Policy must produce between 1 and 16 chunks");
    UUID generation=UUID.randomUUID();var chunks=new ArrayList<M5PolicyChunk>();
    for(String text:texts) {
      deadline.check();float[] vector=M5EmbeddingAdapter.validate(provider.document(text,deadline),768);
      chunks.add(new M5PolicyChunk(UUID.randomUUID(),id,generation,chunks.size()+1,text,vector.clone()));
    }
    deadline.check();
    var published=repository.publish(captured,chunks,space,chunkerVersion,deadline);
    return indexed(published,!generation.equals(published.activeGenerationId()));
  }
  private M5PolicyDocument get(UUID id,M5WorkDeadline deadline) {
    if(id==null) throw validation("Policy id is required");
    return repository.find(id,deadline).orElseThrow(()->new M5ApiException(404,"POLICY_NOT_FOUND","Policy document was not found"));
  }
  private M5PolicyDtos.Index indexed(M5PolicyDocument doc,boolean replay) {
    return new M5PolicyDtos.Index(doc.id(),doc.activeGenerationId(),doc.embeddingSpaceId(),doc.chunkerVersion(),doc.chunkCount(),doc.version(),"mock".equals(settings.mode()),replay);
  }
  private static M5WorkDeadline deadline() {return new M5WorkDeadline(Duration.ofSeconds(5),"POLICY_TIMEOUT");}
  private static M5ApiException validation(String message) {return new M5ApiException(400,"VALIDATION",message);}
}
