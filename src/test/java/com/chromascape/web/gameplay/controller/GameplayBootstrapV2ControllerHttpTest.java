package com.chromascape.web.gameplay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.gameplay.bootstrap.BootstrapV2DraftService;
import com.chromascape.web.gameplay.orchestration.BootstrapGameplayOperatorService.OperatorException;
import com.chromascape.web.gameplay.orchestration.BootstrapV2OperatorService;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Mode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** The v2 operator surface exists, requires the operator key and maps refusals truthfully. */
class GameplayBootstrapV2ControllerHttpTest {
  private static final String OPERATOR_KEY = "0123456789abcdef0123456789abcdef";
  private static final Instant NOW = Instant.parse("2026-08-30T12:05:00Z");

  private BootstrapV2OperatorService operatorService;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    operatorService = mock(BootstrapV2OperatorService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(new GameplayBootstrapV2Controller(operatorService))
            .build();
  }

  @Test
  void previewReturnsTheScopeTheOwnerMustApprove() throws Exception {
    when(operatorService.preview(anyString(), any()))
        .thenReturn(
            new BootstrapV2OperatorService.DraftView(
                "draft-req-1",
                "req-1",
                "recording-1",
                "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64),
                "sha256:" + "c".repeat(64),
                NOW.plusSeconds(300),
                JsonNodeFactory.instance.objectNode(),
                JsonNodeFactory.instance.objectNode(),
                NOW));

    mvc.perform(
            post("/api/v1/gameplay/bootstrap/v2/drafts")
                .header(GameplayBootstrapV2Controller.OPERATOR_KEY_HEADER, OPERATOR_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"providerSessionId\":\"session-1\",\"providerTurnId\":\"turn-1\","
                        + "\"deadline\":\"2026-08-30T12:10:00Z\",\"requestId\":\"req-1\","
                        + "\"idempotencyKey\":\"idem-1\",\"authorityId\":\"authority-1\","
                        + "\"approvedBy\":\"operator-1\","
                        + "\"approvalExpiresAt\":\"2026-08-30T12:15:00Z\","
                        + "\"leaseGeneration\":1,\"fence\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scopeDigest").value("sha256:" + "a".repeat(64)))
        .andExpect(jsonPath("$.requestId").value("req-1"));
  }

  @Test
  void dispatchReturnsTheDurableDeliveryState() throws Exception {
    when(operatorService.dispatch(OPERATOR_KEY, "approval-1"))
        .thenReturn(view(State.RESERVED));

    mvc.perform(
            post("/api/v1/gameplay/bootstrap/v2/approvals/approval-1/dispatch")
                .header(GameplayBootstrapV2Controller.OPERATOR_KEY_HEADER, OPERATOR_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("RESERVED"));
  }

  @Test
  void dispatchRefusalIsReportedWithItsStableCode() throws Exception {
    when(operatorService.dispatch(OPERATOR_KEY, "approval-1"))
        .thenThrow(
            new OperatorException(
                HttpStatus.CONFLICT, "APPROVAL_EXPIRED", "The approval has expired"));

    mvc.perform(
            post("/api/v1/gameplay/bootstrap/v2/approvals/approval-1/dispatch")
                .header(GameplayBootstrapV2Controller.OPERATOR_KEY_HEADER, OPERATOR_KEY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.failureCode").value("APPROVAL_EXPIRED"));
  }

  @Test
  void boundedWaitRequiresAnExplicitDeadline() throws Exception {
    mvc.perform(
            post("/api/v1/gameplay/bootstrap/v2/runs/orch-1/await")
                .header(GameplayBootstrapV2Controller.OPERATOR_KEY_HEADER, OPERATOR_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.failureCode").value("INVALID_REQUEST"));
  }

  @Test
  void boundedWaitPassesTheDeadlineThrough() throws Exception {
    when(operatorService.awaitTerminal(OPERATOR_KEY, "orch-1", NOW.plusSeconds(60)))
        .thenReturn(view(State.CONFIRMED));

    mvc.perform(
            post("/api/v1/gameplay/bootstrap/v2/runs/orch-1/await")
                .header(GameplayBootstrapV2Controller.OPERATOR_KEY_HEADER, OPERATOR_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deadline\":\"2026-08-30T12:06:00Z\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CONFIRMED"));
    verify(operatorService).awaitTerminal(OPERATOR_KEY, "orch-1", NOW.plusSeconds(60));
  }

  @Test
  void runRequiresTheOperatorKey() throws Exception {
    mvc.perform(get("/api/v1/gameplay/bootstrap/v2/runs/orch-1"))
        .andExpect(status().isBadRequest());
  }

  private OrchestrationView view(State state) {
    return new OrchestrationView(
        AccountBuilderOrchestrationModels.VERSION,
        "orch-1",
        "req-1",
        "idem-1",
        "sha256:" + "b".repeat(64),
        "sha256:" + "c".repeat(64),
        Mode.BOOTSTRAP_RECORDING,
        "PROVIDER:CLAUDE",
        state,
        FailureCode.NONE,
        null,
        List.of(),
        true,
        false,
        1,
        NOW,
        NOW);
  }
}
