package com.fluxpay.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fluxpay.beans.*;
import com.fluxpay.config.*;
import com.fluxpay.dto.*;
import com.fluxpay.repository.M5PolicyRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class M5PolicyServiceUnitTest {
  final M5PolicyRepository repo=mock(M5PolicyRepository.class);
  final M5VectorSettings settings=new M5VectorSettings("mock","","nomic-embed-text","1","",768,3,400,700,50,5,.35);
  final M5EmbeddingAdapter provider=spy(new M5EmbeddingAdapter(settings));
  final M5PolicyChunker chunker=new M5PolicyChunker(400,700,50);
  final M5PolicyService service=new M5PolicyService(repo,chunker,provider,settings);
  @Test void createUsesContentHashAndRejectsNormalizedDuplicateWithExistingId() {
    var doc=document(null,null,null,0);
    when(repo.findByHash(anyString(),any())).thenReturn(Optional.of(doc));
    var e=assertThrows(M5ApiException.class,()->service.create(new M5PolicyDtos.Create("Different title","KYC","  Synthetic policy.\r\n")));
    assertEquals("DUPLICATE_POLICY",e.code()); assertEquals(doc.id().toString(),e.fieldErrors().get("existingPolicyDocumentId"));
    verify(repo,never()).create(any(),any());
  }
  @Test void creationPersistsNormalizedImmutableUnindexedDocument() {
    when(repo.findByHash(anyString(),any())).thenReturn(Optional.empty());
    when(repo.create(any(),any())).thenAnswer(i->i.getArgument(0));
    var doc=service.create(new M5PolicyDtos.Create("  Synthetic title ","KYC","  Synthetic policy.\r\n"));
    assertNotNull(doc); assertEquals("Synthetic policy.",doc.content());
    assertEquals("Synthetic title",doc.title()); assertEquals("UNINDEXED",doc.indexState());
    assertNull(doc.activeGenerationId()); assertEquals(0,doc.version());
  }
  @Test void sameConfigurationReplaysWithoutEmbedding() {
    var doc=document(UUID.randomUUID(),settings.spaceId(),"m5-sentence-v1:400:700:50",1);
    when(repo.find(eq(doc.id()),any())).thenReturn(Optional.of(doc));
    var indexed=service.index(doc.id());
    assertNotNull(indexed); assertTrue(indexed.replayed()); assertEquals(doc.activeGenerationId(),indexed.generationId());
    verify(provider,never()).document(anyString(),any());
  }
  @Test void failedEmbeddingNeverPublishesAndAllVectorsPrecedePublication() {
    var doc=document(null,null,null,0);
    when(repo.find(eq(doc.id()),any())).thenReturn(Optional.of(doc));
    doThrow(new M5ApiException(503,"EMBEDDING_UNAVAILABLE","upstream failure")).when(provider).document(anyString(),any());
    assertEquals("EMBEDDING_UNAVAILABLE",assertThrows(M5ApiException.class,()->service.index(doc.id())).code());
    verify(repo,never()).publish(any(),anyList(),anyString(),anyString(),any());
  }
  @Test void indexingPublishesWholeGenerationAndReturnsMetadata() {
    var doc=document(null,null,null,0);
    when(repo.find(eq(doc.id()),any())).thenReturn(Optional.of(doc));
    when(repo.publish(eq(doc),anyList(),anyString(),anyString(),any())).thenAnswer(i->{
      List<M5PolicyChunk> chunks=i.getArgument(1);
      assertEquals(1,chunks.size()); assertEquals(768,chunks.get(0).embedding().length);
      assertEquals(1,chunks.get(0).chunkNumber()); assertEquals(doc.id(),chunks.get(0).policyDocumentId());
      return new M5PolicyDocument(doc.id(),doc.title(),doc.category(),doc.content(),doc.documentHash(),doc.createdAt(),1,"INDEXED",chunks.get(0).generationId(),i.getArgument(2),i.getArgument(3),1);
    });
    var result=service.index(doc.id()); assertNotNull(result); assertFalse(result.replayed()); assertTrue(result.mock()); assertEquals(1,result.chunkCount());
    var ordered=inOrder(provider,repo); ordered.verify(provider).document(anyString(),any()); ordered.verify(repo).publish(eq(doc),anyList(),anyString(),anyString(),any());
  }
  @Test void validationAndMissingDocumentMapToDefinedFailures() {
    assertEquals("VALIDATION",assertThrows(M5ApiException.class,()->service.create(new M5PolicyDtos.Create("x","BAD","text"))).code());
    assertThrows(M5ApiException.class,()->service.list(-1,20)); assertThrows(M5ApiException.class,()->service.list(0,101));
    when(repo.find(any(),any())).thenReturn(Optional.empty());
    assertEquals("POLICY_NOT_FOUND",assertThrows(M5ApiException.class,()->service.get(UUID.randomUUID())).code());
  }
  M5PolicyDocument document(UUID generation,String space,String chunker,long version) { return new M5PolicyDocument(UUID.randomUUID(),"Synthetic title","KYC","Synthetic policy.","hash",Instant.EPOCH,version,generation==null?"UNINDEXED":"INDEXED",generation,space,chunker,generation==null?0:1); }
}
