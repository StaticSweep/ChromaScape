package com.chromascape.web.gameplay.orchestration.accountbuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.FailureCode;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Lease;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.OrchestrationView;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.ProviderOrchestrationCommand;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.Readiness;
import com.chromascape.web.gameplay.orchestration.accountbuilder.AccountBuilderOrchestrationModels.State;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountBuilderOrchestrationControllerHttpTest {
  private static final String OPERATOR_KEY = "0123456789abcdef0123456789abcdef";
  private AccountBuilderOrchestrationService service;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(AccountBuilderOrchestrationService.class);
    AccountBuilderOrchestrationProperties properties = new AccountBuilderOrchestrationProperties();
    // Default-off: no enablement flags are set; only the operator key is configured.
    properties.setOperatorKey(OPERATOR_KEY);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new AccountBuilderOrchestrationController(service, properties))
            .build();
  }

  private OrchestrationView view(State state) {
    return new OrchestrationView(
        AccountBuilderOrchestrationModels.VERSION,
        "orchestration-1",
        "request-1",
        "idempotency-1",
        "sha256:" + "a".repeat(64),
        "sha256:" + "b".repeat(64),
        AccountBuilderOrchestrationModels.Mode.HELD,
        "PROVIDER:CLAUDE",
        state,
        FailureCode.NONE,
        new Lease("reservation-1", 1, 1, "sha256:" + "c".repeat(64), Instant.now()),
        List.of(),
        false,
        false,
        1,
        Instant.now(),
        Instant.now());
  }

  @Test
  void authenticationHappensBeforeRequestProcessing() {
    when(service.orchestrate(any()))
        .thenReturn(new AccountBuilderOrchestrationService.Submission(view(State.HELD), true));
    assertThat(service).isNotNull();
    try {
      mvc.perform(
              post("/api/v1/gameplay/accountbuilder/orchestrations")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{}"))
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.failureCode").value("OPERATOR_UNAUTHORIZED"));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
    verify(service, never()).orchestrate(any());
  }

  @Test
  void wrongOperatorKeyFailsClosed() throws Exception {
    mvc.perform(
            get("/api/v1/gameplay/accountbuilder/readiness")
                .header("X-Gameplay-Operator-Key", "not-the-key"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void readinessDoesNotImplyLiveDeliveryWhenDisabled() throws Exception {
    when(service.readiness())
        .thenReturn(
            new Readiness(
                AccountBuilderOrchestrationModels.VERSION,
                false,
                true,
                false,
                false,
                null,
                FailureCode.GATEWAY_UNAVAILABLE));
    mvc.perform(
            get("/api/v1/gameplay/accountbuilder/readiness")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.liveDeliveryReady").value(false));
  }

  @Test
  void missingBodyDoesNotProduceAccidentalNpe() throws Exception {
    mvc.perform(
            post("/api/v1/gameplay/accountbuilder/orchestrations")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY))
        .andExpect(status().is4xxClientError());
    verify(service, never()).orchestrate(any());
  }

  @Test
  void stopReasonLengthLimitIsEnforcedOverHttp() throws Exception {
    when(service.stop(anyString(), anyString(), anyBoolean()))
        .thenThrow(
            new AccountBuilderOrchestrationException(
                FailureCode.STOP_REJECTED, "A bounded stop requires active orchestration state"));
    mvc.perform(
            post("/api/v1/gameplay/accountbuilder/orchestrations/orchestration-1/stop")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .param("reason", "r".repeat(300))
                .content("{\"reason\":\"" + "r".repeat(300) + "\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.failureCode").value("STOP_REJECTED"));
  }

  @Test
  void reconcileCannotChangeATerminalResult() throws Exception {
    when(service.find("orchestration-1")).thenReturn(view(State.CONFIRMED));
    when(service.reconcile("orchestration-1")).thenReturn(view(State.CONFIRMED));
    mvc.perform(
            post("/api/v1/gameplay/accountbuilder/orchestrations/orchestration-1/reconcile")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CONFIRMED"));
  }

  @Test
  void errorResponsesCarryContractVersionAndStableFailureCode() throws Exception {
    when(service.find("unknown-1"))
        .thenThrow(
            new AccountBuilderOrchestrationException(
                FailureCode.INVALID_CONTRACT, "Orchestration was not found"));
    mvc.perform(
            get("/api/v1/gameplay/accountbuilder/orchestrations/unknown-1")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.contractVersion").value(AccountBuilderOrchestrationModels.VERSION))
        .andExpect(jsonPath("$.failureCode").value("INVALID_CONTRACT"));
  }

  @Test
  void responsesNeverEchoSecrets() throws Exception {
    when(service.readiness())
        .thenReturn(
            new Readiness(
                AccountBuilderOrchestrationModels.VERSION,
                false,
                true,
                false,
                false,
                null,
                FailureCode.GATEWAY_UNAVAILABLE));
    String body =
        mvc.perform(
                get("/api/v1/gameplay/accountbuilder/readiness")
                    .header("X-Gameplay-Operator-Key", OPERATOR_KEY))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).doesNotContain(OPERATOR_KEY);
  }

  @Test
  void directProviderSourceBypassIsRejected() throws Exception {
    mvc.perform(
            post("/api/v1/gameplay/accountbuilder/orchestrations")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"orchestrationId\":\"orchestration-1\",\"requestId\":\"request-1\","
                        + "\"idempotencyKey\":\"idempotency-1\",\"sourceType\":\"PROVIDER:CODEX\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.failureCode").value("INVALID_CONTRACT"));
    verify(service, never()).orchestrate(any());
  }

  @Test
  void genericRouteRejectsProviderLikeSourcesRegardlessOfCase() throws Exception {
    for (String source : List.of("provider:CODEX", "Provider:CODEX", "PROVIDER:codex")) {
      mvc.perform(
              post("/api/v1/gameplay/accountbuilder/orchestrations")
                  .header("X-Gameplay-Operator-Key", OPERATOR_KEY)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"orchestrationId\":\"orchestration-1\",\"requestId\":\"request-1\","
                          + "\"idempotencyKey\":\"idempotency-1\",\"sourceType\":\""
                          + source
                          + "\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.failureCode").value("INVALID_CONTRACT"));
    }
    verify(service, never()).orchestrate(any());
  }

  @Test
  void providerOrchestrationRouteDelegatesToTheSingleServiceBoundary() throws Exception {
    when(service.orchestrateProvider(any(ProviderOrchestrationCommand.class)))
        .thenReturn(new AccountBuilderOrchestrationService.Submission(view(State.CREATED), true));
    mvc.perform(
            post("/api/v1/gameplay/accountbuilder/provider-orchestrations")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"providerSessionId\":\"session-1\",\"providerTurnId\":\"turn-1\","
                        + "\"orchestrationId\":\"orchestration-1\",\"requestId\":\"request-1\","
                        + "\"idempotencyKey\":\"idempotency-1\",\"mode\":\"LIVE_TASK\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("CREATED"));
    verify(service).orchestrateProvider(any(ProviderOrchestrationCommand.class));
  }

  @Test
  void providerOrchestrationRouteRequiresAuthentication() throws Exception {
    mvc.perform(
            post("/api/v1/gameplay/accountbuilder/provider-orchestrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"providerSessionId\":\"session-1\",\"providerTurnId\":\"turn-1\","
                        + "\"orchestrationId\":\"orchestration-1\",\"requestId\":\"request-1\","
                        + "\"idempotencyKey\":\"idempotency-1\",\"mode\":\"LIVE_TASK\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.failureCode").value("OPERATOR_UNAUTHORIZED"));
    verify(service, never()).orchestrateProvider(any(ProviderOrchestrationCommand.class));
  }

  @Test
  void providerOrchestrationRouteFailsClosedOnSourceRejection() throws Exception {
    when(service.orchestrateProvider(any(ProviderOrchestrationCommand.class)))
        .thenThrow(
            new AccountBuilderOrchestrationException(
                FailureCode.INVALID_CONTRACT,
                "Persisted provider turn is not a proven DECIDED turn with complete provenance"));
    mvc.perform(
            post("/api/v1/gameplay/accountbuilder/provider-orchestrations")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"providerSessionId\":\"session-1\",\"providerTurnId\":\"turn-1\","
                        + "\"orchestrationId\":\"orchestration-1\",\"requestId\":\"request-1\","
                        + "\"idempotencyKey\":\"idempotency-1\",\"mode\":\"LIVE_TASK\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.failureCode").value("INVALID_CONTRACT"));
  }

  @Test
  void providerOrchestrationRouteReturnsOkForAnIdempotentReplay() throws Exception {
    OrchestrationView existing = view(State.DENIED);
    when(service.orchestrateProvider(any(ProviderOrchestrationCommand.class)))
        .thenReturn(new AccountBuilderOrchestrationService.Submission(existing, false));
    mvc.perform(
            post("/api/v1/gameplay/accountbuilder/provider-orchestrations")
                .header("X-Gameplay-Operator-Key", OPERATOR_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"providerSessionId\":\"session-1\",\"providerTurnId\":\"turn-1\","
                        + "\"orchestrationId\":\"orchestration-1\",\"requestId\":\"request-1\","
                        + "\"idempotencyKey\":\"idempotency-1\",\"mode\":\"LIVE_TASK\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("DENIED"));
  }
}
