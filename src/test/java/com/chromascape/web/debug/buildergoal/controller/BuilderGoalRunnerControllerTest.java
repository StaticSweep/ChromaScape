package com.chromascape.web.debug.buildergoal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.buildergoal.service.BuilderGoalLaunchOperationService;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalLauncherControlService;
import com.chromascape.web.debug.buildergoal.service.BuilderGoalRunnerService;
import com.chromascape.web.debug.buildergoal.service.Micro6OnlineClientRegistryService;
import com.fasterxml.jackson.databind.JsonNode;
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
            builderGoalRunnerService, launcherControlService, clientRegistryService, objectMapper);
  }

  @Test
  void listMachines_returnsHttp200WithMachineSummaries() {
    Map<String, Object> machine = new LinkedHashMap<>();
    machine.put("machineUuid", "1d391d65-6c97-5b77-b676-964f157e8c73");
    machine.put("displayName", "CZC8518C27 (Beheerder)");
    machine.put("online", true);
    machine.put("localMachine", true);
    machine.put("builderGoalRunnable", true);
    when(launcherControlService.listMachineSummaries()).thenReturn(List.of(machine));

    ResponseEntity<Object> response = controller.listMachines();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
    assertThat(body).hasSize(1);
    assertThat(body.get(0).get("machineUuid")).isEqualTo("1d391d65-6c97-5b77-b676-964f157e8c73");
  }

  @Test
  void getRunSummary_returnsRunSummary() {
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("iterationId", "buildergoal_123");
    run.put("targetPreparationState", "BUILDING_MICRO6");
    when(builderGoalRunnerService.getRunSummary("buildergoal_123")).thenReturn(run);

    ResponseEntity<Object> response = controller.getRunSummary("buildergoal_123");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isEqualTo(run);
    verify(builderGoalRunnerService).getRunSummary("buildergoal_123");
  }

  @Test
  void getRunSummary_returnsNotFoundForMissingRun() {
    when(builderGoalRunnerService.getRunSummary("missing"))
        .thenThrow(new IllegalArgumentException("Builder goal run not found: missing"));

    ResponseEntity<Object> response = controller.getRunSummary("missing");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("error")).isEqualTo("Builder goal run not found: missing");
  }

  @Test
  void listMachines_returnsBadGatewayWithStructuredErrorWhenLauncherFails() {
    when(launcherControlService.listMachineSummaries())
        .thenThrow(new IllegalStateException("HTTP 401: unauthorized"));
    when(launcherControlService.describeSanitizedFailure(any()))
        .thenReturn("HTTP 401: unauthorized");

    ResponseEntity<Object> response = controller.listMachines();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("Failed to load launcher machines");
    assertThat(String.valueOf(body.get("details"))).contains("HTTP 401");
  }

  @Test
  void listMachines_badGatewayDetailsNeverDiscloseConfiguredToken() {
    when(launcherControlService.listMachineSummaries())
        .thenThrow(new IllegalStateException("HTTP 401: echo secret-token"));
    when(launcherControlService.describeSanitizedFailure(any())).thenReturn("HTTP 401: echo ***");

    ResponseEntity<Object> response = controller.listMachines();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.toString()).doesNotContain("secret-token");
  }

  @Test
  void listMachines_badGatewayResponseContainsNeitherTokenNorAuthorizationHeader() {
    when(launcherControlService.listMachineSummaries())
        .thenThrow(
            new IllegalStateException("HTTP 401: Authorization: Bearer secret-token expired"));
    when(launcherControlService.describeSanitizedFailure(any()))
        .thenReturn("HTTP 401: Authorization: Bearer *** expired");

    ResponseEntity<Object> response = controller.listMachines();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("Failed to load launcher machines");
    assertThat(String.valueOf(body.get("details")))
        .isNotNull()
        .isNotBlank()
        .doesNotContain("secret-token");
    assertThat(body.toString()).doesNotContain("secret-token");
  }

  @Test
  void listMachines_badGatewayDetailsAreNonNullForParsingFailures() {
    when(launcherControlService.listMachineSummaries())
        .thenThrow(
            new IllegalStateException(
                "Failed to load launcher machines: JsonParseException: Unexpected token"));
    when(launcherControlService.describeSanitizedFailure(any()))
        .thenReturn("JsonParseException: Unexpected token");

    ResponseEntity<Object> response = controller.listMachines();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("Failed to load launcher machines");
    assertThat(String.valueOf(body.get("details")))
        .contains("JsonParseException")
        .isNotNull()
        .isNotBlank();
  }

  @Test
  void genericControllerFailureUsesSanitizedDetails() {
    when(builderGoalRunnerService.importPayload(any()))
        .thenThrow(
            new RuntimeException(
                "upstream response Authorization: Bearer secret-token should not leak"));
    when(launcherControlService.describeSanitizedFailure(any()))
        .thenReturn(
            "RuntimeException: upstream response Authorization: Bearer *** should not leak");

    ResponseEntity<Object> response = controller.importPayload(objectMapper.createObjectNode());

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("error")).isEqualTo("Failed to import builder goal payload");
    assertThat(String.valueOf(body.get("details")))
        .contains("Bearer ***")
        .doesNotContain("secret-token");
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

    ResponseEntity<Object> response =
        controller.saveRecoveryAnnotations("buildergoal_rec0001", null);

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

    ResponseEntity<Object> response =
        controller.resolveRecoveryEvidence("buildergoal_rec0001", null);

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

    ResponseEntity<Object> response =
        controller.openRecoveryWorkspaceByRecordingId("recording_123");

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
    when(builderGoalRunnerService.getRecoveryStateByRecordingId("recording_123")).thenReturn(state);

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
            anyBoolean(),
            any()))
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
            anyBoolean(),
            any());
  }

  @Test
  void publishTask_rejectsCallerSelectedTarget() throws Exception {
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            "{\"accountId\":\"hash-1\",\"taskKey\":\"FISHING_TRAINING\",\"targetBaseUrl\":\"http://127.0.0.1:8098\"}");

    ResponseEntity<Object> response = controller.publishTask(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body)
        .containsEntry("error", "TARGET_OVERRIDE_NOT_ALLOWED")
        .containsEntry(
            "details", "Dispatch targets are selected only from a matching ready registry client.");
  }

  @Test
  void publishTask_rejectsNestedCallerSelectedTarget() throws Exception {
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            "{\"accountId\":\"hash-1\",\"taskKey\":\"FISHING_TRAINING\","
                + "\"task_metadata\":{\"target_base_url\":\"http://127.0.0.1:8098\"}}");

    ResponseEntity<Object> response = controller.publishTask(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "TARGET_OVERRIDE_NOT_ALLOWED");
  }

  @Test
  void publishTask_rejectsNullSettings() throws Exception {
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            "{\"accountId\":\"hash-1\",\"taskKey\":\"FISHING_TRAINING\",\"settings\":null}");

    ResponseEntity<Object> response = controller.publishTask(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("error")).isEqualTo("settings must be a JSON object");
  }

  @Test
  void runPayload_rejectsNonObjectSettings() throws Exception {
    ResponseEntity<Object> response =
        controller.runPayload(
            objectMapper.readTree(
                "{\"accountId\":\"hash-1\",\"taskKey\":\"FISHING_TRAINING\",\"settings\":null}"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "settings must be a JSON object");
  }

  @Test
  void runPayload_forwardsLegacyRawSettingsWithoutInjectingEmptyEnvelope() throws Exception {
    when(builderGoalRunnerService.startRun(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(Map.of("accepted", true));

    JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "world": 360,
              "builderPayload": {
                "task_key": "configurable_woodcutting",
                "title": "Woodcutting",
                "world": 360,
                "treeType": "WILLOW"
              }
            }
            """);

    ResponseEntity<Object> response = controller.runPayload(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    org.mockito.ArgumentCaptor<JsonNode> payloadCaptor =
        org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(builderGoalRunnerService)
        .startRun(payloadCaptor.capture(), any(), any(), any(), any(), any(), any(), any());
    JsonNode forwarded = payloadCaptor.getValue();
    assertThat(forwarded.has("settings")).isFalse();
    assertThat(forwarded.path("treeType").asText()).isEqualTo("WILLOW");
  }

  @Test
  void runPayload_rejectsCallerSelectedTarget() throws Exception {
    ResponseEntity<Object> response =
        controller.runPayload(
            objectMapper.readTree(
                "{\"accountId\":\"hash-1\",\"taskKey\":\"FISHING_TRAINING\",\"targetBaseUrl\":\"http://127.0.0.1:8098\"}"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "TARGET_OVERRIDE_NOT_ALLOWED");
  }

  @Test
  void launchAccount_rejectsNestedCallerSelectedTarget() throws Exception {
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            "{\"accountId\":\"hash-1\",\"launcherMachineUuid\":\"machine-1\","
                + "\"world\":360,\"task_metadata\":{\"targetBaseUrl\":\"http://127.0.0.1:8098\"}}");

    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "TARGET_OVERRIDE_NOT_ALLOWED");
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

  @Test
  void launchAccount_delegatesToServiceAndReturnsAccepted() throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operationId", "op-123");
    result.put("status", BuilderGoalLaunchOperationService.STATUS_QUEUED);
    result.put("accountId", "hash-1");
    result.put("accountName", "bigGreenMeat");
    result.put("launcherMachineUuid", "machine-1");
    result.put("world", 360);
    result.put("deduplicated", false);
    result.put("statusUrl", "/api/debug/builder-goals/launch-operations/op-123");
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", 360)).thenReturn(result);

    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "world": 360
            }
            """);
    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.get("operationId")).isEqualTo("op-123");
    assertThat(body.get("status")).isEqualTo("QUEUED");
    assertThat(body.get("accountId")).isEqualTo("hash-1");
    assertThat(body.get("accountName")).isEqualTo("bigGreenMeat");
    assertThat(body.get("launcherMachineUuid")).isEqualTo("machine-1");
    assertThat(body.get("world")).isEqualTo(360);
    assertThat(body.get("deduplicated")).isEqualTo(false);
    assertThat(body.get("statusUrl"))
        .isEqualTo("/api/debug/builder-goals/launch-operations/op-123");
    verify(builderGoalRunnerService).launchAccount("hash-1", "machine-1", 360);
  }

  @Test
  void launchAccount_returnsAcceptedWithDeduplicatedFlagForActiveOperation() throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("operationId", "op-123");
    result.put("status", BuilderGoalLaunchOperationService.STATUS_QUEUED);
    result.put("accountId", "hash-1");
    result.put("accountName", "bigGreenMeat");
    result.put("launcherMachineUuid", "machine-1");
    result.put("world", 360);
    result.put("deduplicated", true);
    result.put("statusUrl", "/api/debug/builder-goals/launch-operations/op-123");
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", 360)).thenReturn(result);

    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "world": 360
            }
            """);
    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("deduplicated")).isEqualTo(true);
  }

  @Test
  void launchAccount_returnsBadRequestWhenAccountMissing() throws Exception {
    when(builderGoalRunnerService.launchAccount(null, "machine-1", null))
        .thenThrow(new IllegalArgumentException("accountId is required"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "launcherMachineUuid": "machine-1"
            }
            """);

    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "accountId is required");
  }

  @Test
  void launchAccount_returnsConflictWhenLaunchCredentialsAreMissing() throws Exception {
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", 360))
        .thenThrow(
            new IllegalStateException(
                "Selected launcher account is missing an email for local Micro6 relaunch"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "world": 360
            }
            """);

    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body)
        .containsEntry(
            "error", "Selected launcher account is missing an email for local Micro6 relaunch");
  }

  @Test
  void getLaunchOperation_returnsCurrentState() {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("operationId", "op-123");
    view.put("status", "WAITING_FOR_DISPATCH");
    view.put("message", "Micro6 local executor is waiting for login");
    view.put("readinessPhase", "WAITING_FOR_DISPATCH");
    view.put("terminal", false);
    view.put("success", false);
    when(builderGoalRunnerService.getLaunchOperation("op-123")).thenReturn(view);

    ResponseEntity<Object> response = controller.getLaunchOperation("op-123");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body.get("operationId")).isEqualTo("op-123");
    assertThat(body.get("status")).isEqualTo("WAITING_FOR_DISPATCH");
    assertThat(body.get("terminal")).isEqualTo(false);
  }

  @Test
  void getLaunchOperation_returnsNotFoundForUnknownOrExpired() {
    when(builderGoalRunnerService.getLaunchOperation("missing"))
        .thenThrow(new IllegalArgumentException("Launch operation not found or expired: missing"));

    ResponseEntity<Object> response = controller.getLaunchOperation("missing");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "Launch operation not found or expired: missing");
  }

  @Test
  void getLaunchOperation_sanitizesUnexpectedFailuresWithoutCredentials() {
    when(builderGoalRunnerService.getLaunchOperation("op-123"))
        .thenThrow(
            new IllegalStateException(
                "upstream response Authorization: Bearer secret-token should not leak"));
    when(launcherControlService.describeSanitizedFailure(any()))
        .thenReturn(
            "IllegalStateException: upstream response Authorization: Bearer *** should not leak");

    ResponseEntity<Object> response = controller.getLaunchOperation("op-123");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(String.valueOf(body.get("details")))
        .contains("Bearer ***")
        .doesNotContain("secret-token");
  }

  @Test
  void launchAccount_returnsBadRequestWhenWorldMissing() throws Exception {
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", null))
        .thenThrow(new IllegalArgumentException("world must be between 300 and 700"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1"
            }
            """);

    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "world must be between 300 and 700");
  }

  @Test
  void launchAccount_returnsBadRequestWhenWorldBelowRange() throws Exception {
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", 299))
        .thenThrow(new IllegalArgumentException("world must be between 300 and 700"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "world": 299
            }
            """);

    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "world must be between 300 and 700");
  }

  @Test
  void launchAccount_returnsBadRequestWhenWorldAboveRange() throws Exception {
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", 701))
        .thenThrow(new IllegalArgumentException("world must be between 300 and 700"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "world": 701
            }
            """);

    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "world must be between 300 and 700");
  }

  @Test
  void runPayload_returnsConflictWhenLaunchCredentialsAreMissing() throws Exception {
    when(builderGoalRunnerService.startCatalogTaskRun(
            eq("hash-1"),
            eq("machine-1"),
            eq("configurable_woodcutting"),
            any(),
            any(),
            eq(360),
            any()))
        .thenThrow(
            new IllegalStateException(
                "Selected launcher account is missing a password for local Micro6 relaunch"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "taskKey": "configurable_woodcutting",
              "world": 360
            }
            """);

    ResponseEntity<Object> response = controller.runPayload(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body)
        .containsEntry(
            "error", "Selected launcher account is missing a password for local Micro6 relaunch");
  }

  @Test
  void publishTask_returnsConflictWhenDisabledAccountRejectsPublish() throws Exception {
    when(builderGoalRunnerService.publishOnly(
            eq("hash-1"),
            eq("machine-1"),
            eq("configurable_woodcutting"),
            any(),
            eq(360),
            anyBoolean(),
            any()))
        .thenThrow(new IllegalStateException("Selected launcher account is disabled"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "taskKey": "configurable_woodcutting",
              "world": 360
            }
            """);

    ResponseEntity<Object> response = controller.publishTask(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("error", "Selected launcher account is disabled");
  }

  @Test
  void launchAccount_passesForceRelaunch() throws Exception {
    Map<String, Object> result = Map.of("operationId", "op-force");
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", 360, true))
        .thenReturn(result);

    ResponseEntity<Object> response =
        controller.launchAccount(
            objectMapper.readTree(
                "{\"accountId\":\"hash-1\",\"launcherMachineUuid\":\"machine-1\",\"world\":360,\"forceRelaunch\":true}"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    verify(builderGoalRunnerService).launchAccount("hash-1", "machine-1", 360, true);
  }

  @Test
  void mcpHealth_returnsUpWithServiceAndTimestamp() {
    ResponseEntity<?> response = controller.mcpHealth();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isInstanceOf(Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsEntry("status", "UP");
    assertThat(body).containsEntry("service", "chromascape-builder-goal");
    assertThat(body).containsKey("timestamp");
    assertThat((String) body.get("timestamp")).isNotBlank();
  }

  @Test
  void launchAccount_returnsServiceUnavailableWhenExecutorRejects() throws Exception {
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", 360))
        .thenThrow(
            new BuilderGoalRunnerService.LaunchSubmissionException(
                "Launch executor cannot accept the request right now"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "world": 360
            }
            """);

    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(body).containsKey("error");
    assertThat(body).containsKey("guidance");
  }

  @Test
  void launchAccount_errorBodiesAreSanitized() throws Exception {
    when(builderGoalRunnerService.launchAccount("hash-1", "machine-1", 360))
        .thenThrow(
            new IllegalArgumentException(
                "invalid credentials --profile-email=user@example.com:supersecret"));
    com.fasterxml.jackson.databind.JsonNode payload =
        objectMapper.readTree(
            """
            {
              "accountId": "hash-1",
              "launcherMachineUuid": "machine-1",
              "world": 360
            }
            """);

    ResponseEntity<Object> response = controller.launchAccount(payload);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(String.valueOf(body.get("error"))).doesNotContain("supersecret");
    assertThat(String.valueOf(body.get("error"))).contains("********");
  }

  @Test
  void getLaunchOperation_notFoundBodyIsSanitized() {
    when(builderGoalRunnerService.getLaunchOperation("op-1"))
        .thenThrow(
            new IllegalArgumentException(
                "operation not found Authorization: Bearer leaked-secret"));
    ResponseEntity<Object> response = controller.getLaunchOperation("op-1");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) response.getBody();
    assertThat(String.valueOf(body.get("error"))).doesNotContain("leaked-secret");
  }
}
