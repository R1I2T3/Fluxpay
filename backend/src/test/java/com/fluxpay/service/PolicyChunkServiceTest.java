package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PolicyChunk;
import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.common.enums.PolicyChunkSource;
import com.fluxpay.dto.PolicyChunkRequest;
import com.fluxpay.dto.PolicyChunkResponse;
import com.fluxpay.repository.PolicyChunkRepository;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PolicyChunkServiceTest {

  private final PolicyChunkRepository chunkRepository = mock(PolicyChunkRepository.class);
  private final PolicyDocumentRepository documentRepository = mock(PolicyDocumentRepository.class);
  private final PolicyChunkService service = new PolicyChunkService(chunkRepository, documentRepository);

  @Test
  void updateAllowsAHandWrittenChunkAndMarksItManualInTheResponse() {
    UUID policyId = UUID.randomUUID();
    UUID chunkId = UUID.randomUUID();
    PolicyChunk chunk = chunk(chunkId, policyId, PolicyChunkSource.MANUAL, "Original guidance");
    when(chunkRepository.findById(chunkId)).thenReturn(Optional.of(chunk));
    when(chunkRepository.save(chunk)).thenReturn(chunk);

    PolicyChunkResponse result =
        service.updateChunk(policyId, chunkId, new PolicyChunkRequest("Updated guidance"));

    assertThat(result.content()).isEqualTo("Updated guidance");
    assertThat(result.manual()).isTrue();
  }

  @Test
  void updateRejectsAnIndexedChunk() {
    UUID policyId = UUID.randomUUID();
    UUID chunkId = UUID.randomUUID();
    PolicyChunk chunk = chunk(chunkId, policyId, PolicyChunkSource.GENERATED, "Indexed text");
    when(chunkRepository.findById(chunkId)).thenReturn(Optional.of(chunk));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.updateChunk(policyId, chunkId, new PolicyChunkRequest("Changed text")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Only manually added chunks can be edited");
  }

  @Test
  void deleteAllowsAnIndexedChunk() {
    UUID policyId = UUID.randomUUID();
    UUID chunkId = UUID.randomUUID();
    PolicyChunk chunk = chunk(chunkId, policyId, PolicyChunkSource.GENERATED, "Indexed text");
    when(chunkRepository.findById(chunkId)).thenReturn(Optional.of(chunk));

    service.deleteChunk(policyId, chunkId);

    verify(chunkRepository).delete(chunk);
  }

  private static PolicyChunk chunk(UUID id, UUID policyId, PolicyChunkSource source, String content) {
    PolicyDocument document = new PolicyDocument();
    ReflectionTestUtils.setField(document, "id", policyId);
    PolicyChunk chunk = new PolicyChunk();
    ReflectionTestUtils.setField(chunk, "id", id);
    ReflectionTestUtils.setField(chunk, "source", source);
    chunk.setPolicyDocument(document);
    chunk.setChunkNumber(1);
    chunk.setContent(content);
    return chunk;
  }
}
