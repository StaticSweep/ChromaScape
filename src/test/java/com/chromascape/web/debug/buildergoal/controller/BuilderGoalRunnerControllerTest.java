package com.chromascape.web.debug.buildergoal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalLauncherControlService;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalRunnerService;
import com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Unit tests for the recovery-mode endpoints added to {@link BuilderGoalRunnerController}.
 *
 * <p>Verifies that the new structured recovery endpoints delegate to the service and return the
 * expected status codes for success, bad-request, and conflict cases.
 */
@ExtendWith(MockitoExtension.class)
class BuilderGoalRunnerControllerTest {

  @Mock private BuilderGoalRunnerService builderGoalRunnerService;
  @Mock private BuilderGoalLauncherControlService launcherControlService;
  @Mock private Micro6OnlineClientRegistryService clientRegistryService;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private BuilderGoalRunnerController controller;

  @BeforeEach
  void setUp() {
    controller =
        new BuilderGoalRunnerController(
            builderGoalRunnerService,
            launcherControlService,
            clientRegistryService,
            objectMapper);
  }

  @Test
  void openRecoveryWorkspace_delegatesToServiceAndReturnsOk() {
    Map<String, Object> workspace = new LinkedHashMap<>();
    workspace.put("iterationId", "buildergoal_rec0001");
    workspace.put("recoveryState", "AWAITING_OPERATOR_PROMPT");
    when(builderGoalRunnerService.openRecoveryWorkspace("buildergoal_rec0001"))
        .thenReturn(workspace);

    ResponseEntity<Object> response = controller.openRecoveryWorkspace("buildergoal_rec0001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("recoveryState")).isEqualTo("AWAITING_OPERATOR_PROMPT");
  }

  @Test
  void openRecoveryWorkspace_returnsBadRequestWhenRunMissing() {
    when(builderGoalRunnerService.openRecoveryWorkspace("missing"))
        .thenThrow(new IllegalArgumentException("Builder goal run not found: missing"));

    ResponseEntity<Object> response = controller.openRecoveryWorkspace("missing");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsKey("error");
  }

  @Test
  void saveRecoveryAnnotations_delegatesAndReturnsOk() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("saved", true);
    result.put("annotations", List.of());
    when(builderGoalRunnerService.saveRecoveryAnnotations(eq("buildergoal_rec0001"), eq(null)))
        .thenReturn(result);

    ResponseEntity<Object> response = controller.saveRecoveryAnnotations("buildergoal_rec0001", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("saved")).isEqualTo(true);
  }

  @Test
  void resolveRecoveryEvidence_returnsOk() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("selectedEvidenceCount", 2);
    result.put("totalEvidenceCount", 5);
    when(builderGoalRunnerService.resolveRecoveryEvidence(eq("buildergoal_rec0001"), eq(null)))
        .thenReturn(result);

    ResponseEntity<Object> response = controller.resolveRecoveryEvidence("buildergoal_rec0001", null);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("selectedEvidenceCount")).isEqualTo(2);
  }

  @Test
  void getRecoveryState_returnsOkWithPhaseAndChild() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("recoveryPhase", "WAITING_FOR_DEBUG_BIND");
    state.put("preferredSessionId", "debug_123");
    state.put("childIterationId", "buildergoal_child0001");
    Map<String, Object> childRun = new LinkedHashMap<>();
    childRun.put("runRecordingId", "rec_child_123");
    childRun.put("runSessionId", "debug_123");
    state.put("childRun", childRun);
    when(builderGoalRunnerService.getRecoveryState("buildergoal_rec0001")).thenReturn(state);

    ResponseEntity<Object> response = controller.getRecoveryState("buildergoal_rec0001");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("recoveryPhase")).isEqualTo("WAITING_FOR_DEBUG_BIND");
    assertThat(body.get("preferredSessionId")).isEqualTo("debug_123");
    assertThat(body.get("childIterationId")).isEqualTo("buildergoal_child0001");
    assertThat(body.get("childRun")).isNotNull();
    @SuppressWarnings("unchecked")
    Map<String, Object> bodyChildRun = (Map<String, Object>) body.get("childRun");
    assertThat(bodyChildRun.get("runRecordingId")).isEqualTo("rec_child_123");
  }

  @Test
  void getRecoveryState_returnsBadRequestWhenNotFound() {
    when(builderGoalRunnerService.getRecoveryState("missing"))
        .thenThrow(new IllegalArgumentException("not found"));

    ResponseEntity<Object> response = controller.getRecoveryState("missing");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  void getRunByRecordingId_returnsResolvedRun() {
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("iterationId", "buildergoal_rec0001");
    run.put("runRecordingId", "recording_123");
    when(builderGoalRunnerService.getRunSummaryByRecordingId("recording_123")).thenReturn(run);

    ResponseEntity<Object> response = controller.getRunByRecordingId("recording_123");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("iterationId")).isEqualTo("buildergoal_rec0001");
  }

  @Test
  void getRunLogsByRecordingId_returnsResolvedLogs() {
    Map<String, Object> logs = new LinkedHashMap<>();
    logs.put("success", true);
    logs.put("historicalLogs", List.of(Map.of("message", "task log")));
    when(builderGoalRunnerService.getRunLogsByRecordingId("recording_123")).thenReturn(logs);

    ResponseEntity<Object> response = controller.getRunLogsByRecordingId("recording_123");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("success")).isEqualTo(true);
  }

  @Test
  void openRecoveryWorkspaceByRecordingId_delegatesToServiceAndReturnsOk() {
    Map<String, Object> workspace = new LinkedHashMap<>();
    workspace.put("iterationId", "buildergoal_rec0001");
    workspace.put("recordingId", "recording_123");
    when(builderGoalRunnerService.openRecoveryWorkspaceByRecordingId("recording_123"))
        .thenReturn(workspace);

    ResponseEntity<Object> response = controller.openRecoveryWorkspaceByRecordingId("recording_123");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("recordingId")).isEqualTo("recording_123");
  }

  @Test
  void getRecoveryStateByRecordingId_returnsResolvedState() {
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("iterationId", "buildergoal_rec0001");
    state.put("recoveryPhase", "AWAITING_OPERATOR_PROMPT");
    when(builderGoalRunnerService.getRecoveryStateByRecordingId("recording_123"))
        .thenReturn(state);

    ResponseEntity<Object> response = controller.getRecoveryStateByRecordingId("recording_123");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("recoveryPhase")).isEqualTo("AWAITING_OPERATOR_PROMPT");
  }

  @Test
  void publishTask_delegatesToServiceAndReturnsOk() throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("accepted", true);
    result.put("published", true);
    result.put("entryId", 42);
    when(builderGoalRunnerService.publishOnly(
            eq("hash-1"),
            eq("machine-1"),
            eq("configurable_woodcutting"),
            any(),
            eq(360),
            eq("http://127.0.0.1:8098"),
            anyBoolean()))
        .thenReturn(result);

    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "taskKey": "configurable_woodcutting",
              "world": 360,
              "settings": {"treeType": "WILLOW"},
              "targetBaseUrl": "http://127.0.0.1:8098",
              "allowLaunchFallback": false
            }
            """);
    ResponseEntity<Object> response = controller.publishTask(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("accepted")).isEqualTo(true);
    assertThat(body.get("published")).isEqualTo(true);
    assertThat(body.get("entryId")).isEqualTo(42);
    verify(builderGoalRunnerService)
        .publishOnly(
            eq("hash-1"),
            eq("machine-1"),
            eq("configurable_woodcutting"),
            any(),
            eq(360),
            eq("http://127.0.0.1:8098"),
            anyBoolean());
  }

  @Test
  void publishTask_returnsBadRequestWhenAccountAndTaskMissing() throws Exception {
    com.fasterxml.jackson.databind.JsonNode payload = objectMapper.readTree("{}");
    ResponseEntity<Object> response = controller.publishTask(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsKey("error");
  }
}
