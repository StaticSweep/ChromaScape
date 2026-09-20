package com.chromascape.web.gameplay.bootstrap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.gameplay.controller.GameplayBootstrapController;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

// Standalone MockMvc is used deliberately: the repository pins spring-test 6.1.6, which predates
// the MockMvcTester type Boot 3.5.3's @WebMvcTest slice expects, so any slice context fails to
// load. The HTTP contract assertions need only the controller, its local exception handlers and
// Jackson; no Spring context is required.
@ExtendWith(MockitoExtension.class)
class GameplayBootstrapControllerHttpTest {
  private MockMvc mockMvc;
  private ObjectMapper objectMapper;

  @Mock private BootstrapGameplayOperatorService operatorService;

  @BeforeEach
  void setUp() {
    objectMapper = JsonMapper.builder().findAndAddModules().build();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new GameplayBootstrapController(operatorService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
  }

  @Test
  void availabilityWithoutHeaderExposesOnlyEnabledFlag() throws Exception {
    when(operatorService.publicAvailability()).thenReturn(Map.of("enabled", false));

    mockMvc
        .perform(get("/api/v1/gameplay/bootstrap/availability"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.providerModels").doesNotExist())
        .andExpect(jsonPath("$.blockingReasons").doesNotExist());
  }

  @Test
  void availabilityWithWrongKeyIsUnauthorizedAndExposesNoDetail() throws Exception {
    when(operatorService.availability("wrong-key"))
        .thenThrow(
            new BootstrapGameplayOperatorService.OperatorException(
                org.springframework.http.HttpStatus.UNAUTHORIZED,
                "OPERATOR_AUTHENTICATION_FAILED",
                "A valid SC-1498 operator key is required"));

    mockMvc
        .perform(
            get("/api/v1/gameplay/bootstrap/availability")
                .header("X-Gameplay-Operator-Key", "wrong-key"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.providerModels").doesNotExist())
        .andExpect(jsonPath("$.blockingReasons").doesNotExist())
        .andExpect(jsonPath("$.code").value("OPERATOR_AUTHENTICATION_FAILED"));
  }

  @Test
  void availabilityWithValidKeyReturnsDetailedReadiness() throws Exception {
    when(operatorService.availability("operator-key"))
        .thenReturn(
            new BootstrapGameplayOperatorService.Availability(
                true, true, true, List.of("CLAUDE:1.0"), 0, List.of()));

    mockMvc
        .perform(
            get("/api/v1/gameplay/bootstrap/availability")
                .header("X-Gameplay-Operator-Key", "operator-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.providerModels[0]").value("CLAUDE:1.0"));
  }

  @Test
  void previewRequiresCompletePersistedDecisionIdentity() throws Exception {
    when(operatorService.preview(eq("operator-key"), any()))
        .thenThrow(
            new BootstrapGameplayOperatorService.OperatorException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "PROPOSAL_INCOMPLETE",
                "A complete proposal is required"));

    mockMvc
        .perform(
            post("/api/v1/gameplay/bootstrap/previews")
                .header("X-Gameplay-Operator-Key", "operator-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("PROPOSAL_INCOMPLETE"));
  }

  @Test
  void approvalWithoutHumanConfirmationIsPreconditionFailed() throws Exception {
    when(operatorService.approve(eq("operator-key"), any()))
        .thenThrow(
            new BootstrapGameplayOperatorService.OperatorException(
                org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                "HUMAN_CONFIRMATION_REQUIRED",
                "The exact displayed scope requires explicit human confirmation"));

    mockMvc
        .perform(
            post("/api/v1/gameplay/bootstrap/approvals")
                .header("X-Gameplay-Operator-Key", "operator-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of(
                            "approvedBy",
                            "operator-1",
                            "scopeDigest",
                            "sha256:" + "a".repeat(64),
                            "humanConfirmed",
                            false))))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("HUMAN_CONFIRMATION_REQUIRED"));
  }

  @Test
  void killWithoutConfirmationIsPreconditionFailed() throws Exception {
    when(operatorService.kill(eq("operator-key"), any()))
        .thenThrow(
            new BootstrapGameplayOperatorService.OperatorException(
                org.springframework.http.HttpStatus.PRECONDITION_FAILED,
                "KILL_CONFIRMATION_REQUIRED",
                "Kill-switch requires explicit confirmation"));

    mockMvc
        .perform(
            post("/api/v1/gameplay/bootstrap/runs/orch-1/kill")
                .header("X-Gameplay-Operator-Key", "operator-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("reason", "test"))))
        .andExpect(status().isPreconditionFailed())
        .andExpect(jsonPath("$.code").value("KILL_CONFIRMATION_REQUIRED"));
  }

  @Test
  void runStatusUsesDurableOrchestrationIdentity() throws Exception {
    var view =
        new com.chromascape.web.gameplay.orchestration.accountbuilder
            .AccountBuilderOrchestrationModels.OrchestrationView(
            "sc-1431/v1",
            "orch-1",
            "req-1",
            "idem-1",
            "sha256:" + "f".repeat(64),
            "sha256:" + "e".repeat(64),
            com.chromascape.web.gameplay.orchestration.accountbuilder
                .AccountBuilderOrchestrationModels.Mode.BOOTSTRAP_RECORDING,
            "PROVIDER:CLAUDE",
            com.chromascape.web.gameplay.orchestration.accountbuilder
                .AccountBuilderOrchestrationModels.State.CONFIRMED,
            com.chromascape.web.gameplay.orchestration.accountbuilder
                .AccountBuilderOrchestrationModels.FailureCode.NONE,
            null,
            List.of(),
            true,
            false,
            3,
            Instant.parse("2026-08-30T12:00:00Z"),
            Instant.parse("2026-08-30T12:01:00Z"));
    when(operatorService.run("operator-key", "orch-1")).thenReturn(view);

    mockMvc
        .perform(
            get("/api/v1/gameplay/bootstrap/runs/orch-1")
                .header("X-Gameplay-Operator-Key", "operator-key"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orchestrationId").value("orch-1"))
        .andExpect(jsonPath("$.state").value("CONFIRMED"));
  }
}
