package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.PolicyDocument;
import com.fluxpay.common.contracts.PolicyIndexStore;
import com.fluxpay.common.enums.PolicyCategory;
import com.fluxpay.dto.PolicyDocumentRequest;
import com.fluxpay.repository.PolicyChunkRepository;
import com.fluxpay.repository.PolicyDocumentRepository;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class PolicyDocumentServiceTest {

  private final PolicyDocumentRepository repository = mock(PolicyDocumentRepository.class);
  private final PolicyChunkRepository chunkRepository = mock(PolicyChunkRepository.class);
  private final PolicyIndexStore indexStore = mock(PolicyIndexStore.class);
  private final PolicyDocumentService service =
      new PolicyDocumentService(repository, chunkRepository, indexStore);

  @Test
  void updateClearsTheActiveIndexByDefaultBeforeSavingTheChangedPolicy() {
    UUID id = UUID.randomUUID();
    PolicyDocument document = document(id, "Original", PolicyCategory.KYC, "Original text");
    when(repository.findById(id)).thenReturn(Optional.of(document));
    when(repository.findByDocumentHash(any(String.class))).thenReturn(Optional.empty());
    when(repository.save(document)).thenReturn(document);

    service.update(
        id, new PolicyDocumentRequest("Changed", PolicyCategory.AML, "Changed text", null));

    InOrder order = inOrder(indexStore, repository);
    order.verify(indexStore).delete(id);
    order.verify(repository).save(document);
    assertThat(document.getTitle()).isEqualTo("Changed");
    assertThat(document.getCategory()).isEqualTo(PolicyCategory.AML);
    assertThat(document.getContent()).isEqualTo("Changed text");
    assertThat(document.getDocumentHash())
        .isEqualTo("58b2322b2c19615c4020c63dee25d2506ec694b8efd2c54173ea461509dada2b");
  }

  @Test
  void updateRetainsExistingChunksAndIndexWhenRequested() {
    UUID id = UUID.randomUUID();
    PolicyDocument document = document(id, "Original", PolicyCategory.KYC, "Original text");
    when(repository.findById(id)).thenReturn(Optional.of(document));
    when(repository.findByDocumentHash(any(String.class))).thenReturn(Optional.empty());
    when(repository.save(document)).thenReturn(document);

    service.update(
        id, new PolicyDocumentRequest("Changed", PolicyCategory.AML, "Changed text", false));

    verifyNoInteractions(indexStore);
    verify(repository).save(document);
    assertThat(document.getTitle()).isEqualTo("Changed");
  }

  @Test
  void updateRejectsAnUnknownPolicy() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.update(
                    id, new PolicyDocumentRequest("Changed", PolicyCategory.AML, "Text")))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("Policy document not found: " + id);
    verifyNoInteractions(indexStore);
    verify(repository, never()).save(any());
  }

  @Test
  void updateRejectsContentAlreadyOwnedByAnotherPolicyBeforeInvalidatingTheIndex() {
    UUID id = UUID.randomUUID();
    UUID existingId = UUID.randomUUID();
    PolicyDocument document = document(id, "Original", PolicyCategory.KYC, "Original text");
    PolicyDocument existing = document(existingId, "Existing", PolicyCategory.AML, "Changed text");
    when(repository.findById(id)).thenReturn(Optional.of(document));
    when(repository.findByDocumentHash(any(String.class))).thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () ->
                service.update(
                    id, new PolicyDocumentRequest("Changed", PolicyCategory.AML, "Changed text")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Duplicate policy content, existing id: " + existingId);
    verifyNoInteractions(indexStore);
    verify(repository, never()).save(any());
  }

  private static PolicyDocument document(
      UUID id, String title, PolicyCategory category, String content) {
    PolicyDocument document = new PolicyDocument();
    ReflectionTestUtils.setField(document, "id", id);
    document.setTitle(title);
    document.setCategory(category);
    document.setContent(content);
    return document;
  }
}
