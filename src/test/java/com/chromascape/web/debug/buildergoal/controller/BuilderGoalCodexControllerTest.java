package com.chromascape.web.debug.buildergoal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalCodexOrchestrationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
class BuilderGoalCodexControllerTest {

  @Mock private BuilderGoalCodexOrchestrationService orchestrationService;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private BuilderGoalCodexController controller;

  @BeforeEach
  void setUp() {
    controller = new BuilderGoalCodexController(orchestrationService);
  }

  @Test
  void createSession_returnsOk() {
    when(orchestrationService.createSession()).thenReturn(Map.of("sessionId", "codex_bg_1"));

    ResponseEntity<Object> response = controller.createSession();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(Map.of("sessionId", "codex_bg_1"));
  }

  @Test
  void startRun_returnsOk() throws Exception {
    when(orchestrationService.startRun(
            eq("codex_bg_1"),
            eq("account-1"),
            eq("machine-1"),
            eq("task-1"),
            eq(360),
            eq("Task One"),
            any()))
        .thenReturn(Map.of("activeIterationId", "buildergoal_1"));

    ResponseEntity<Object> response =
        controller.startRun(
            "codex_bg_1",
            objectMapper.readTree(
                """
                {
                  "accountId": "account-1",
                  "launcherMachineUuid": "machine-1",
                  "taskKey": "task-1",
                  "world": 360,
                  "displayName": "Task One",
                  "settings": { "treeType": "WILLOW" }
                }
                """));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(Map.of("activeIterationId", "buildergoal_1"));
  }

  @Test
  void processMessage_conflictReturns409() throws Exception {
    when(orchestrationService.processMessage(eq("codex_bg_1"), eq("stop it"), any()))
        .thenThrow(new IllegalStateException("No active run"));

    ResponseEntity<Object> response =
        controller.postMessage(
            "codex_bg_1",
            objectMapper.readTree(
                """
                {
                  "message": "stop it"
                }
                """));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("error")).isEqualTo("No active run");
  }

  @Test
  void getRunLogs_returnsOk() {
    when(orchestrationService.getRunLogs("codex_bg_1", "buildergoal_1"))
        .thenReturn(Map.of("success", true, "matchedLogCount", 3));

    ResponseEntity<Object> response = controller.getRunLogs("codex_bg_1", "buildergoal_1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(Map.of("success", true, "matchedLogCount", 3));
  }

  @Test
  void getReplayContext_returnsOk() {
    when(orchestrationService.getReplayContext("codex_bg_1", "buildergoal_1"))
        .thenReturn(Map.of("debugSessionId", "debug_1", "recordingId", "recording_1"));

    ResponseEntity<Object> response = controller.getReplayContext("codex_bg_1", "buildergoal_1");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody())
        .isEqualTo(Map.of("debugSessionId", "debug_1", "recordingId", "recording_1"));
  }

  @Test
  void getReplayTick_returnsBadRequestWhenSessionMissing() {
    when(orchestrationService.getReplayTick("codex_bg_1", null, 412L))
        .thenThrow(new IllegalArgumentException("Codex session not found: codex_bg_1"));

    ResponseEntity<Object> response = controller.getReplayTick("codex_bg_1", 412L, null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("error")).isEqualTo("Codex session not found: codex_bg_1");
  }

  @Test
  void startRun_preservesRequestIdAndReturnsStableConflict() throws Exception {
    var payload =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(
                "{\"accountId\":\"a\",\"launcherMachineUuid\":\"m\",\"taskKey\":\"fishing\",\"requestId\":\"stable\"}");
    when(orchestrationService.startRun(
            eq("session"),
            eq("a"),
            eq("m"),
            eq("fishing"),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull(),
            any(),
            eq("stable")))
        .thenThrow(
            new com.chromascape.web.debug.buildergoal.service.BuilderGoalStartException(
                HttpStatus.CONFLICT,
                "REQUEST_IDENTITY_CONFLICT",
                "REQUEST_RESERVATION",
                "Conflict",
                null,
                null,
                null,
                null));
    var response = controller.startRun("session", payload);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(
            ((com.chromascape.web.debug.buildergoal.dto.BuilderGoalStartFailureResponse)
                    response.getBody())
                .code())
        .isEqualTo("REQUEST_IDENTITY_CONFLICT");
  }

  @Test
  void startRun_rejectsNonStringRequestIdBeforeDispatch() throws Exception {
    var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"requestId\":42}");
    assertThat(controller.startRun("session", payload).getStatusCode())
        .isEqualTo(HttpStatus.BAD_REQUEST);
    org.mockito.Mockito.verifyNoInteractions(orchestrationService);
  }
}
