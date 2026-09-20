package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.common.security.*;
import com.fluxpay.dto.KycStatusResponse;
import com.fluxpay.service.KycUploadService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(KycDocumentController.class)
@Import({SecurityConfig.class, MethodSecurityConfig.class})
class KycDocumentControllerTest {
  @Autowired MockMvc mvc;
  @MockBean KycUploadService uploads;
  @MockBean JwtUtil jwt;
  UUID owner = UUID.randomUUID();
  UUID id = UUID.randomUUID();

  @BeforeEach
  void setup() {
    when(jwt.parse("owner")).thenReturn(new CurrentUser(owner, "owner@example.test", "CUSTOMER"));
  }

  @Test
  void multipartUploadReturnsCreated() throws Exception {
    when(uploads.submit(eq(owner), any(), eq("TEST123"), any()))
        .thenReturn(new KycStatusResponse(id, 0L, KycStatus.PENDING, null, Instant.now(), null));
    mvc.perform(
            multipart("/api/kyc/applications")
                .file(
                    new MockMultipartFile(
                        "files", "identity.pdf", "application/pdf", "%PDF-1.4\n%%EOF".getBytes()))
                .param("docType", "PASSPORT")
                .param("docNumber", "TEST123")
                .header("Authorization", "Bearer owner"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("PENDING"));
  }

  @Test
  void downloadIsAuthenticatedPrivateAndNonExecutable() throws Exception {
    when(uploads.read(any(), eq(id)))
        .thenReturn(
            new KycUploadService.Content("identity.pdf", "application/pdf", "%PDF".getBytes()));
    mvc.perform(get("/api/kyc/documents/{id}/content", id).header("Authorization", "Bearer owner"))
        .andExpect(status().isOk())
        .andExpect(content().bytes("%PDF".getBytes()))
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"));
  }

  @Test
  void anonymousCannotReadOrUpload() throws Exception {
    mvc.perform(get("/api/kyc/documents/{id}/content", id)).andExpect(status().isUnauthorized());
    mvc.perform(
            multipart("/api/kyc/applications")
                .file(new MockMultipartFile("files", "x.pdf", "application/pdf", "x".getBytes()))
                .param("docType", "PAN")
                .param("docNumber", "TEST"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(uploads);
  }

  @Test
  void inaccessibleDocumentReturnsNotFound() throws Exception {
    when(uploads.read(any(), eq(id)))
        .thenThrow(new com.fluxpay.exception.KycException("KYC_NOT_FOUND", "Document not found."));
    mvc.perform(get("/api/kyc/documents/{id}/content", id).header("Authorization", "Bearer owner"))
        .andExpect(status().isNotFound());
  }
}
