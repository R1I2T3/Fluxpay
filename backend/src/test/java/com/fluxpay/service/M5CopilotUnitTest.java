package com.fluxpay.service;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.config.*;
import com.fluxpay.dto.*;
import com.fluxpay.repository.M5PolicyRepository;
import java.util.*;
import org.junit.jupiter.api.Test;

class M5CopilotUnitTest {
  final M5PolicyRepository repo=mock(M5PolicyRepository.class);
  final M5CaseContextReader cases=mock(M5CaseContextReader.class);
  final M5VectorSettings settings=new M5VectorSettings("mock","","nomic-embed-text","1","",768,3,400,700,50,5,.35);
  final M5EmbeddingAdapter provider=spy(new M5EmbeddingAdapter(settings));
  final M5CopilotService service=new M5CopilotService(repo,provider,settings,cases);
  final CurrentUser actor=new CurrentUser(UUID.randomUUID(),"synthetic@example.invalid","ADMIN");
  @Test void emptyEligibleCorpusGivesExactNoAnswerAndNoSources() {
    when(repo.search(any(),anyString(),anyInt(),anyDouble(),any())).thenReturn(List.of());
    var answer=service.ask(new M5CopilotDtos.Ask("Synthetic question",null),actor);
    assertNotNull(answer); assertEquals("No grounded answer found in current policies.",answer.answer());
    assertEquals(List.of(),answer.sources()); assertTrue(answer.mock());
  }
  @Test void displayedAnswerContainsOnlyExactSupportingPassagesAtMost300Characters() {
    var id=UUID.randomUUID(); String content="Identity verification is required. "+"Synthetic follow-up details. ".repeat(20);
    when(repo.search(any(),anyString(),anyInt(),anyDouble(),any())).thenReturn(List.of(new M5PolicyDtos.Match(id,"Synthetic policy",2,content,.1)));
    var answer=service.ask(new M5CopilotDtos.Ask("Identity",null),actor);
    assertNotNull(answer); assertEquals(1,answer.sources().size()); var source=answer.sources().get(0);
    assertEquals(id,source.policyDocumentId()); assertEquals(2,source.chunkNumber());
    assertTrue(source.excerpt().length()<=300); assertTrue(content.contains(source.excerpt()));
    assertEquals(source.excerpt(),answer.answer());
  }
  @Test void unicodeExcerptDoesNotSplitSurrogateOrExceed300JavaCharacters() {
    String content="😀".repeat(301); UUID id=UUID.randomUUID();
    when(repo.search(any(),anyString(),anyInt(),anyDouble(),any())).thenReturn(
        List.of(new M5PolicyDtos.Match(id,"Synthetic policy",1,content,.1)));
    String excerpt=service.ask(new M5CopilotDtos.Ask("Unicode",null),actor).sources().get(0).excerpt();
    assertTrue(excerpt.length()<=300); assertTrue(content.contains(excerpt));
    assertFalse(Character.isHighSurrogate(excerpt.charAt(excerpt.length()-1)));
  }
  @Test void citedExcerptPreservesStoredCanonicalWhitespace() {
    String content="First rule.\nSecond\trule with exact supporting text."; UUID id=UUID.randomUUID();
    when(repo.search(any(),anyString(),anyInt(),anyDouble(),any())).thenReturn(
        List.of(new M5PolicyDtos.Match(id,"Whitespace policy",1,content,.1)));
    var result=service.ask(new M5CopilotDtos.Ask("What is the second rule?",null),actor);
    assertEquals(content,result.answer());
    assertEquals(content,result.sources().get(0).excerpt());
    assertTrue(content.contains(result.sources().get(0).excerpt()));
  }
  @Test void contextIsAuthorizedBeforeEmbeddingAndNotSentToProvider() {
    UUID payment=UUID.randomUUID(); var context=Map.<String,Object>of("risk","HIGH","paymentId",payment,"privateSnapshot","never-send");
    when(cases.context(eq(payment),eq(actor),any(M5WorkDeadline.class))).thenReturn(context);
    when(repo.search(any(),anyString(),anyInt(),anyDouble(),any())).thenReturn(List.of());
    var result=service.ask(new M5CopilotDtos.Ask("Why flagged?",payment),actor);
    assertNotNull(result); assertEquals(context,result.caseContext());
    var order=inOrder(cases,provider); order.verify(cases).context(eq(payment),eq(actor),any(M5WorkDeadline.class)); order.verify(provider).query(eq("Why flagged?"),any());
  }
  @Test void questionLimitCountsUnicodeCodePointsAndDenialStopsProvider() {
    assertThrows(M5ApiException.class,()->service.ask(new M5CopilotDtos.Ask(" ",null),actor));
    assertThrows(M5ApiException.class,()->service.ask(new M5CopilotDtos.Ask("😀".repeat(1001),null),actor));
    when(repo.search(any(),anyString(),anyInt(),anyDouble(),any())).thenReturn(List.of());
    assertNotNull(service.ask(new M5CopilotDtos.Ask("😀".repeat(1000),null),actor));
    clearInvocations(provider);
    UUID id=UUID.randomUUID(); when(cases.context(eq(id),eq(actor),any(M5WorkDeadline.class))).thenThrow(new M5ApiException(404,"CASE_NOT_FOUND","missing"));
    assertThrows(M5ApiException.class,()->service.ask(new M5CopilotDtos.Ask("Why?",id),actor)); verify(provider,never()).query(anyString(),any());
  }
}
