package com.fluxpay.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.KycDocument;
import com.fluxpay.repository.KycCaseRepository;
import com.fluxpay.repository.KycDocumentRepository;
import com.fluxpay.repository.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles({"oracle-it", "m1-solo"})
class OracleM1ApiIntegrationTest {
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository users;
  @Autowired private KycCaseRepository kycCases;
  @Autowired private KycDocumentRepository kycDocuments;

  private UUID userId;
  private UUID applicationId;

  @AfterEach
  void cleanUp() {
    if (applicationId != null) {
      List<KycDocument> documents =
          kycDocuments.findAllByKycCaseIdOrderByUploadedAtAsc(applicationId);
      kycDocuments.deleteAll(documents);
      kycDocuments.flush();
      kycCases.deleteById(applicationId);
      kycCases.flush();
    }
    if (userId != null) {
      users.deleteById(userId);
      users.flush();
    }
  }

  @Test
  void registerProfileAndKycSubmissionUseDedicatedOracleSchema() throws Exception {
    String email = "oracle.it." + UUID.randomUUID() + "@fluxpay.test";
    String registerBody =
        "{\"email\":\""
            + email
            + "\",\"password\":\"Pass123!\",\"fullName\":\"Oracle Integration User\"}";

    String registerResponse =
        mockMvc
            .perform(
                post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.user.email").value(email))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode registered = objectMapper.readTree(registerResponse).path("data");
    userId = UUID.fromString(registered.path("user").path("id").asText());
    String token = registered.path("token").asText();

    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(userId.toString()))
        .andExpect(jsonPath("$.data.kycStatus").value("NONE"));

    String submitResponse =
        mockMvc
            .perform(
                post("/api/kyc/applications")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"docType\":\"PAN\",\"docNumber\":\"ABCDE1234F\",\"documents\":[{\"fileName\":\"pan.pdf\",\"fileType\":\"application/pdf\",\"fileSize\":1024}]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    applicationId =
        UUID.fromString(
            objectMapper.readTree(submitResponse).path("data").path("applicationId").asText());

    assertThat(users.findByCanonicalEmail(email)).isPresent();
    assertThat(kycCases.findByUserId(userId)).isPresent();
    List<KycDocument> documents =
        kycDocuments.findAllByKycCaseIdOrderByUploadedAtAsc(applicationId);
    assertThat(documents)
        .singleElement()
        .satisfies(
            document -> {
              assertThat(document.getFileName()).isEqualTo("pan.pdf");
              assertThat(document.getFileSize()).isEqualTo(1024);
            });
  }
}
