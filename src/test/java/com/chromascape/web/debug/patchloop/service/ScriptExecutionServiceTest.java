package com.chromascape.web.debug.patchloop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.buildergoal.service.CanonicalSettingsHasher;
import com.chromascape.web.instance.WebSocketStateHandler;
import com.chromascape.web.recording.service.TaskDetectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScriptExecutionServiceTest {

  @Mock private WebSocketStateHandler stateHandler;
  @Mock private TaskDetectionService taskDetectionService;
  @Mock private HttpClient httpClient;
  @Mock private HttpResponse<String> executeResponse;
  @Mock private HttpResponse<String> statusResponse;

  private ScriptExecutionService service;

  @BeforeEach
  void setUp() {
    service =
        new ScriptExecutionService(
            stateHandler,
            taskDetectionService,
            new ObjectMapper(),
            "micro6_local_executor",
            "http://127.0.0.1:8098",
            httpClient,
            0L); // Zero timeout-recovery probe window keeps ambiguous-timeout tests fast.
  }

  private String acceptedBody(int entryId, String taskKey) {
    return "{\"success\":true,\"status\":\"ACCEPTED\",\"activation_state\":\"RECEIVED\","
        + "\"entry_id\":"
        + entryId
        + ",\"task_key\":\""
        + taskKey
        + "\",\"queued_task\":\""
        + taskKey
        + "\"}";
  }

  private ScriptExecutionService serviceWithProbeWindow(long probeMs) {
    return new ScriptExecutionService(
        stateHandler,
        taskDetectionService,
        new ObjectMapper(),
        "micro6_local_executor",
        "http://127.0.0.1:8098",
        httpClient,
        probeMs);
  }

  /**
   * Test topology trusts the given fixture hosts explicitly; the default service trusts loopback
   * plus the default launcher/executor hosts only.
   */
  private ScriptExecutionService serviceTrusting(String... hosts) {
    return new ScriptExecutionService(
        stateHandler,
        taskDetectionService,
        new ObjectMapper(),
        "micro6_local_executor",
        "http://127.0.0.1:8098",
        httpClient,
        0L,
        null,
        Set.of(hosts));
  }

  @Test
  @DisplayName("Micro6 execute accepts only a matching immutable settings receipt")
  void publishMicro6Task_parsesVerifiedSettingsReceipt() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String settingsHash = CanonicalSettingsHasher.sha256(mapper, Map.of());
    String body =
        mapper.writeValueAsString(
            Map.of(
                "success",
                true,
                "status",
                "ACCEPTED",
                "entry_id",
                1001,
                "task_key",
                "FISHING_TRAINING",
                "settings_receipt",
                Map.ofEntries(
                    Map.entry("version", "builder-settings-receipt/v1"),
                    Map.entry("contract", "builder-settings-canonical-json/v1"),
                    Map.entry("entry_id", 1001),
                    Map.entry("iteration_id", "buildergoal_receipt"),
                    Map.entry("task_key", "FISHING_TRAINING"),
                    Map.entry("settings", Map.of()),
                    Map.entry("settings_sha256", settingsHash),
                    Map.entry("settings_schema_version", "1"),
                    Map.entry("settings_schema_hash", "sha256:schema"),
                    Map.entry("accepted_at", "2026-08-16T00:00:00Z"),
                    Map.entry("configuration_state", "PENDING"))));
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(body);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:FISHING_TRAINING",
            Map.of(
                "task_key",
                "FISHING_TRAINING",
                "iteration_id",
                "buildergoal_receipt",
                "settings",
                Map.of(),
                "requires_settings_receipt",
                true));

    assertThat(result.published()).isTrue();
    assertThat(result.settingsReceipt()).isNotNull();
    assertThat(result.settingsReceipt().settingsSha256()).isEqualTo(settingsHash);
  }

  @Test
  @DisplayName("Micro6 backend returns COMPLETED terminal status")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6Completed() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "MyScript"));
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"COMPLETED\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("MyScript", 1000);

    assertThat(result.success()).isTrue();
    assertThat(result.backend()).isEqualTo("micro6_local_executor");
    assertThat(result.terminalStatus()).isEqualTo("COMPLETED");
    assertThat(result.timedOut()).isFalse();
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  @DisplayName("Micro6 backend returns FAILED terminal status with error message")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6Failed() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "MyScript"));
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"FAILED\",\"error_message\":\"boom\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("MyScript", 1000);

    assertThat(result.success()).isFalse();
    assertThat(result.backend()).isEqualTo("micro6_local_executor");
    assertThat(result.terminalStatus()).isEqualTo("FAILED");
    assertThat(result.timedOut()).isFalse();
    assertThat(result.errorMessage()).isEqualTo("boom");
  }

  @Test
  @DisplayName("Micro6 backend returns timeout terminal result when no terminal status arrives")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6Timeout() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "MyScript"));
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"RUNNING\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result = service.runScriptAndWait("MyScript", 1);

    assertThat(result.success()).isFalse();
    assertThat(result.backend()).isEqualTo("micro6_local_executor");
    assertThat(result.terminalStatus()).isEqualTo("TIMEOUT");
    assertThat(result.timedOut()).isTrue();
    assertThat(result.errorMessage()).contains("timed out");
  }

  @Test
  @DisplayName("Micro6 backend handles execute endpoint rejection")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6ExecuteRejected() throws Exception {
    when(executeResponse.statusCode()).thenReturn(503);
    when(executeResponse.body()).thenReturn("unavailable");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("MyScript", 1000);

    assertThat(result.success()).isFalse();
    assertThat(result.backend()).isEqualTo("micro6_local_executor");
    assertThat(result.terminalStatus()).isEqualTo("FAILED");
    assertThat(result.errorMessage()).contains("HTTP 503");
  }

  @Test
  @DisplayName("Micro6 backend dispatches to targeted advertised base URL when provided")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6UsesTargetBaseUrl() throws Exception {
    ScriptExecutionService lanService = serviceTrusting("192.168.1.77");
    when(executeResponse.statusCode()).thenReturn(200);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "configurable_woodcutting"));
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn(
            "{\"status\":\"COMPLETED\",\"entry_id\":1001,"
                + "\"task_key\":\"configurable_woodcutting\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    Map<String, Object> taskMetadata =
        Map.of(
            "task_key",
            "configurable_woodcutting",
            "micro6_target",
            Map.of("advertised_base_url", "http://192.168.1.77:8098"));

    ScriptExecutionService.ScriptExecutionResult result =
        lanService.runScriptAndWait(
            "execution-1", "micro6:configurable_woodcutting", 1000, taskMetadata);

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, org.mockito.Mockito.times(2))
        .send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

    assertThat(requestCaptor.getAllValues().get(0).uri().toString())
        .startsWith("http://192.168.1.77:8098/api/v1/execute");
    assertThat(requestCaptor.getAllValues().get(1).uri().toString())
        .startsWith("http://192.168.1.77:8098/api/v1/runs/");
    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("Micro6 dispatch prefers the trusted local executor URL over an advertised URL")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6PrefersLocalExecutorBaseUrl() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "FISHING_TRAINING"));
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn(
            "{\"status\":\"COMPLETED\",\"entry_id\":1001,\"task_key\":\"FISHING_TRAINING\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    Map<String, Object> taskMetadata =
        Map.of(
            "task_key",
            "FISHING_TRAINING",
            "micro6_target",
            Map.of("advertised_base_url", "http://100.120.100.78:8098"),
            "target_preparation",
            Map.of("local_executor_base_url", "http://127.0.0.1:8098"));

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("execution-1", "micro6:FISHING_TRAINING", 1000, taskMetadata);

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, org.mockito.Mockito.times(2))
        .send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(requestCaptor.getAllValues().get(0).uri().toString())
        .startsWith("http://127.0.0.1:8098/api/v1/execute");
    assertThat(requestCaptor.getAllValues().get(1).uri().toString())
        .startsWith("http://127.0.0.1:8098/api/v1/runs/");
    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("Micro6 publish returns accepted entry id without waiting for completion")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_returnsPublishedResult() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "configurable_woodcutting"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isTrue();
    // HTTP acceptance is mapped to ACCEPTED, not PUBLISHED; "task started" only appears on RUNNING.
    assertThat(result.publishStatus()).isEqualTo("ACCEPTED");
    assertThat(result.executorStatus()).isEqualTo("ACCEPTED");
    assertThat(result.entryId()).isNotNull();
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  @DisplayName("Micro6 execute HTTP 200 with a malformed body is not marked published")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_malformedBodyIsNotPublished() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn("this is not json");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
  }

  @Test
  @DisplayName("Micro6 execute with a mismatched entry id fails closed")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_mismatchedEntryIdFailsClosed() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(999, "configurable_woodcutting"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
  }

  @Test
  @DisplayName("Micro6 execute with a mismatched task identity fails closed")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_mismatchedTaskIdentityFailsClosed() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "some_other_task"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
  }

  @Test
  @DisplayName("Micro6 publish keeps command provenance separate from click telemetry")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_separatesCommandProvenanceFromClickTelemetry() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "configurable_woodcutting"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    service.publishMicro6Task(
        "micro6:configurable_woodcutting",
        Map.of(
            "task_key", "configurable_woodcutting",
            "initiated_by", "user",
            "input_origin", "manual",
            "user_clicked", true,
            "script_clicked", false));

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

    JsonNode payload = new ObjectMapper().readTree(bodyOf(requestCaptor.getValue()));
    assertThat(payload.path("initiated_by").asText()).isEqualTo("user");
    assertThat(payload.path("input_origin").asText()).isEqualTo("manual");
    assertThat(payload.has("user_clicked")).isFalse();
    assertThat(payload.has("script_clicked")).isFalse();
    assertThat(payload.path("task_metadata").path("user_clicked").asBoolean()).isTrue();
    assertThat(payload.path("task_metadata").path("script_clicked").asBoolean()).isFalse();
  }

  @Test
  @DisplayName(
      "Micro6 publish returns RECONCILING when execute times out twice and no acceptance is confirmed")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_returnsReconcilingAfterAmbiguousTimeout() throws Exception {
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new HttpTimeoutException("request timed out"));

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
    assertThat(result.entryId()).isNotNull();
    assertThat(result.errorMessage()).contains("awaiting executor confirmation");
  }

  @Test
  @DisplayName("runScriptAndWait returns RECONCILING (not FAILED) on ambiguous execute timeout")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6AmbiguousTimeoutReconciles() throws Exception {
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenThrow(new HttpTimeoutException("request timed out"));

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("micro6:configurable_woodcutting", 1000);

    assertThat(result.success()).isFalse();
    assertThat(result.terminalStatus()).isEqualTo("RECONCILING");
    assertThat(result.timedOut()).isFalse();
    assertThat(result.errorMessage()).contains("awaiting executor confirmation");
  }

  @Test
  @DisplayName("Micro6 publish attaches to a verified already-queued run after a 409 conflict")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_attachesToVerifiedAlreadyQueuedRun() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337,"
                + "\"task_key\":\"configurable_woodcutting\",\"existing_status\":\"RUNNING\"}");
    HttpResponse<String> statusProbe = org.mockito.Mockito.mock(HttpResponse.class);
    when(statusProbe.statusCode()).thenReturn(200);
    when(statusProbe.body())
        .thenReturn(
            "{\"status\":\"RUNNING\",\"entry_id\":1337,\"task_key\":\"configurable_woodcutting\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse)
        .thenReturn(statusProbe);

    ScriptExecutionService.Micro6PublishResult result =
        serviceWithProbeWindow(5000)
            .publishMicro6Task(
                "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isTrue();
    assertThat(result.publishStatus()).isEqualTo("RUNNING");
    assertThat(result.entryId()).isEqualTo(1337);
    assertThat(result.attachedToExisting()).isTrue();
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  @DisplayName(
      "Micro6 conflict with mismatched task_key fails closed as DUPLICATE_OR_CONFLICTING_DISPATCH")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictTaskKeyMismatchRejectsAttachment() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337,"
                + "\"task_key\":\"mining_training\",\"existing_status\":\"RUNNING\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("DUPLICATE_OR_CONFLICTING_DISPATCH");
    assertThat(result.attachedToExisting()).isFalse();
    assertThat(result.errorMessage()).contains("different task");
  }

  @Test
  @DisplayName(
      "Micro6 conflict missing identity fields fails closed (never attaches on entry id alone)")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictMissingIdentityFieldsFailsClosed() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn("{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("PUBLISH_FAILED");
    assertThat(result.errorMessage()).contains("rejected task");
  }

  @Test
  @DisplayName("Micro6 conflict with invalid existing_entry_id fails closed")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictInvalidEntryIdFailsClosed() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":0,"
                + "\"task_key\":\"configurable_woodcutting\",\"existing_status\":\"RUNNING\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("PUBLISH_FAILED");
  }

  @Test
  @DisplayName(
      "Micro6 conflict whose polled entry id mismatches fails closed as conflicting dispatch")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictPolledEntryIdMismatchRejectsAttachment() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337,"
                + "\"task_key\":\"configurable_woodcutting\",\"existing_status\":\"RUNNING\"}");
    HttpResponse<String> statusProbe = org.mockito.Mockito.mock(HttpResponse.class);
    when(statusProbe.statusCode()).thenReturn(200);
    when(statusProbe.body())
        .thenReturn(
            "{\"status\":\"RUNNING\",\"entry_id\":9999,\"task_key\":\"configurable_woodcutting\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse)
        .thenReturn(statusProbe);

    ScriptExecutionService.Micro6PublishResult result =
        serviceWithProbeWindow(5000)
            .publishMicro6Task(
                "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("DUPLICATE_OR_CONFLICTING_DISPATCH");
    assertThat(result.errorMessage()).contains("mismatched entry id");
  }

  @Test
  @DisplayName("Micro6 conflict whose polled task identity mismatches rejects attachment")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictPolledTaskMismatchRejectsAttachment() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337,"
                + "\"task_key\":\"configurable_woodcutting\",\"existing_status\":\"RUNNING\"}");
    HttpResponse<String> statusProbe = org.mockito.Mockito.mock(HttpResponse.class);
    when(statusProbe.statusCode()).thenReturn(200);
    when(statusProbe.body())
        .thenReturn("{\"status\":\"RUNNING\",\"entry_id\":1337,\"task_key\":\"mining_training\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse)
        .thenReturn(statusProbe);

    ScriptExecutionService.Micro6PublishResult result =
        serviceWithProbeWindow(5000)
            .publishMicro6Task(
                "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("DUPLICATE_OR_CONFLICTING_DISPATCH");
    assertThat(result.errorMessage()).contains("mismatched task identity");
  }

  @Test
  @DisplayName("Micro6 conflict whose polled status is not attachable rejects attachment")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictUnattachableStatusRejectsAttachment() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337,"
                + "\"task_key\":\"configurable_woodcutting\",\"existing_status\":\"COMPLETED\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse);

    ScriptExecutionService.Micro6PublishResult result =
        serviceWithProbeWindow(5000)
            .publishMicro6Task(
                "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("DUPLICATE_OR_CONFLICTING_DISPATCH");
    assertThat(result.errorMessage()).contains("not in an attachable status");
  }

  @Test
  @DisplayName(
      "Micro6 conflict with unknown snapshot status never attaches on a later runnable poll")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictUnknownSnapshotStatusRejectsAttachment() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337,"
                + "\"task_key\":\"configurable_woodcutting\",\"existing_status\":\"UNKNOWN\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("DUPLICATE_OR_CONFLICTING_DISPATCH");
    assertThat(result.errorMessage()).contains("not in an attachable status");
  }

  @Test
  @DisplayName("Micro6 conflict whose poll never confirms identity enters RECONCILING")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictAmbiguousPollEntersReconciling() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337,"
                + "\"task_key\":\"configurable_woodcutting\",\"existing_status\":\"RUNNING\"}");
    HttpResponse<String> notFoundProbe = org.mockito.Mockito.mock(HttpResponse.class);
    when(notFoundProbe.statusCode()).thenReturn(404);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse)
        .thenReturn(notFoundProbe);

    ScriptExecutionService.Micro6PublishResult result =
        serviceWithProbeWindow(100)
            .publishMicro6Task(
                "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
    assertThat(result.entryId()).isEqualTo(1337);
  }

  @Test
  @DisplayName("Micro6 conflict on a PAUSED existing entry is attachable")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_conflictPausedEntryAttaches() throws Exception {
    HttpResponse<String> conflictResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(conflictResponse.statusCode()).thenReturn(409);
    when(conflictResponse.body())
        .thenReturn(
            "{\"error_code\":\"CONFLICT\",\"existing_entry_id\":1337,"
                + "\"task_key\":\"configurable_woodcutting\",\"existing_status\":\"PAUSED\"}");
    HttpResponse<String> statusProbe = org.mockito.Mockito.mock(HttpResponse.class);
    when(statusProbe.statusCode()).thenReturn(200);
    when(statusProbe.body())
        .thenReturn(
            "{\"status\":\"PAUSED\",\"entry_id\":1337,\"task_key\":\"configurable_woodcutting\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(conflictResponse)
        .thenReturn(statusProbe);

    ScriptExecutionService.Micro6PublishResult result =
        serviceWithProbeWindow(5000)
            .publishMicro6Task(
                "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isTrue();
    assertThat(result.publishStatus()).isEqualTo("PAUSED");
    assertThat(result.entryId()).isEqualTo(1337);
    assertThat(result.attachedToExisting()).isTrue();
  }

  @Test
  @DisplayName("Micro6 status polling resolves target-specific run status")
  @SuppressWarnings("unchecked")
  void getMicro6RunStatus_readsTargetedStatus() throws Exception {
    ScriptExecutionService lanService = serviceTrusting("192.168.1.55");
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"RUNNING\",\"entry_id\":1337}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    ScriptExecutionService.Micro6RunStatusResult result =
        lanService.getMicro6RunStatus(
            Map.of("micro6_target", Map.of("advertised_base_url", "http://192.168.1.55:8098")),
            1337);

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

    assertThat(requestCaptor.getValue().uri().toString())
        .isEqualTo("http://192.168.1.55:8098/api/v1/runs/1337/status");
    assertThat(result.found()).isTrue();
    assertThat(result.status()).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("Micro6 status polling requires the exact task and iteration identity")
  void getMicro6RunStatus_acceptsMatchingTaskAndIterationIdentity() throws Exception {
    ScriptExecutionService lanService = serviceTrusting("192.168.1.55");
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn(
            "{\"status\":\"RUNNING\",\"entry_id\":1337,"
                + "\"task_key\":\"FISHING_TRAINING\",\"iteration_id\":\"iter-1\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    ScriptExecutionService.Micro6RunStatusResult result =
        lanService.getMicro6RunStatus(
            Map.of(
                "task_key", "FISHING_TRAINING",
                "iteration_id", "iter-1",
                "micro6_target", Map.of("advertised_base_url", "http://192.168.1.55:8098")),
            1337);

    assertThat(result.found()).isTrue();
    assertThat(result.status()).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("Micro6 status polling rejects a confirmed task identity mismatch")
  void getMicro6RunStatus_rejectsMismatchedTaskIdentity() throws Exception {
    ScriptExecutionService lanService = serviceTrusting("192.168.1.55");
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn(
            "{\"status\":\"RUNNING\",\"entry_id\":1337,"
                + "\"task_key\":\"MINING_TRAINING\",\"iteration_id\":\"iter-1\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    ScriptExecutionService.Micro6RunStatusResult result =
        lanService.getMicro6RunStatus(
            Map.of(
                "task_key", "FISHING_TRAINING",
                "iteration_id", "iter-1",
                "micro6_target", Map.of("advertised_base_url", "http://192.168.1.55:8098")),
            1337);

    assertThat(result.found()).isFalse();
    assertThat(result.status()).isEqualTo("RECONCILING");
    assertThat(result.errorMessage()).isEqualTo("STATUS_IDENTITY_MISMATCH");
  }

  @Test
  @DisplayName("Micro6 status polling fails closed when iteration identity is missing")
  void getMicro6RunStatus_rejectsMissingIterationIdentity() throws Exception {
    ScriptExecutionService lanService = serviceTrusting("192.168.1.55");
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn(
            "{\"status\":\"RUNNING\",\"entry_id\":1337," + "\"task_key\":\"FISHING_TRAINING\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    ScriptExecutionService.Micro6RunStatusResult result =
        lanService.getMicro6RunStatus(
            Map.of(
                "task_key", "FISHING_TRAINING",
                "iteration_id", "iter-1",
                "micro6_target", Map.of("advertised_base_url", "http://192.168.1.55:8098")),
            1337);

    assertThat(result.found()).isFalse();
    assertThat(result.status()).isEqualTo("RECONCILING");
    assertThat(result.errorMessage()).isEqualTo("STATUS_IDENTITY_MISSING");
  }

  @Test
  @DisplayName(
      "Micro6 executor health reports publish-ready when logged in and AccountBuilder is ready")
  @SuppressWarnings("unchecked")
  void getMicro6ExecutorHealth_reportsPublishReady() throws Exception {
    ScriptExecutionService lanService = serviceTrusting("192.168.1.77");
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn(
            """
            {
              "account_name": "bigGreenMeat",
              "account_hash": "1111111111111111",
              "account_hash_scheme": "accountconnector/v1",
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "requested_world": 360,
              "current_world": 360,
              "world_matched": true,
              "pathfinder_ready": true,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true
            }
            """);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    ScriptExecutionService.Micro6ExecutorHealthResult result =
        lanService.getMicro6ExecutorHealth(
            Map.of(
                "world",
                360,
                "micro6_target",
                Map.of("advertised_base_url", "http://192.168.1.77:8098")));

    assertThat(result.reachable()).isTrue();
    assertThat(result.publishReady()).isTrue();
    assertThat(result.readinessPhase()).isEqualTo("READY_TO_PUBLISH");
    assertThat(result.readinessReason()).isNull();
    assertThat(result.resolvedAccountName()).isEqualTo("bigGreenMeat");
  }

  @Test
  @DisplayName(
      "Micro6 executor health reports concrete dispatch reason when logged in but startup is incomplete")
  @SuppressWarnings("unchecked")
  void getMicro6ExecutorHealth_reportsDispatchReason() throws Exception {
    ScriptExecutionService lanService = serviceTrusting("192.168.1.77");
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn(
            """
            {
              "account_name": "bigGreenMeat",
              "account_hash": "1111111111111111",
              "account_hash_scheme": "accountconnector/v1",
              "dispatch_hold": false,
              "startup_state": "initializing_bot_logic",
              "login_state": "logged_in",
              "requested_world": 360,
              "current_world": 360,
              "world_matched": true,
              "pathfinder_ready": true,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true
            }
            """);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    ScriptExecutionService.Micro6ExecutorHealthResult result =
        lanService.getMicro6ExecutorHealth(
            Map.of(
                "world",
                360,
                "micro6_target",
                Map.of("advertised_base_url", "http://192.168.1.77:8098")));

    assertThat(result.reachable()).isTrue();
    assertThat(result.publishReady()).isFalse();
    assertThat(result.readinessPhase()).isEqualTo("WAITING_FOR_DISPATCH");
    assertThat(result.readinessReason()).contains("still initializing");
  }

  @Test
  @DisplayName("Micro6 health uses account name fallback only with canonical actual identity")
  @SuppressWarnings("unchecked")
  void getMicro6ExecutorHealth_nameFallbackRequiresExactSelectedName() throws Exception {
    ScriptExecutionService lanService = serviceTrusting("192.168.1.77");
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn(
            """
            {
              "account_name": "FallbackName",
              "account_hash": "1111111111111111",
              "account_hash_scheme": "accountconnector/v1",
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "current_world": 360,
              "pathfinder_ready": true,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true
            }
            """);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    ScriptExecutionService.Micro6ExecutorHealthResult matching =
        lanService.getMicro6ExecutorHealth(
            Map.of(
                "world",
                360,
                "micro6_target",
                Map.of(
                    "advertised_base_url",
                    "http://192.168.1.77:8098",
                    "account_name",
                    "fallbackname")));

    assertThat(matching.publishReady()).isTrue();

    when(statusResponse.body())
        .thenReturn(
            """
            {
              "account_name": "FallbackName",
              "account_hash": "not-a-canonical-hash",
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "current_world": 360,
              "pathfinder_ready": true,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true
            }
            """);

    ScriptExecutionService.Micro6ExecutorHealthResult malformedHash =
        lanService.getMicro6ExecutorHealth(
            Map.of(
                "world",
                360,
                "micro6_target",
                Map.of(
                    "advertised_base_url",
                    "http://192.168.1.77:8098",
                    "account_name",
                    "fallbackname")));

    assertThat(malformedHash.publishReady()).isFalse();
    assertThat(malformedHash.readinessPhase()).isEqualTo("WAITING_FOR_ACCOUNT");

    when(statusResponse.body())
        .thenReturn(
            """
            {
              "account_name": "DifferentName",
              "account_hash": "1111111111111111",
              "account_hash_scheme": "accountconnector/v1",
              "dispatch_hold": false,
              "startup_state": "accountbuilder_ready",
              "login_state": "logged_in",
              "current_world": 360,
              "pathfinder_ready": true,
              "script_lifecycle_state": "RUNNING",
              "script_loop_alive": true
            }
            """);

    ScriptExecutionService.Micro6ExecutorHealthResult mismatching =
        lanService.getMicro6ExecutorHealth(
            Map.of(
                "world",
                360,
                "micro6_target",
                Map.of(
                    "advertised_base_url",
                    "http://192.168.1.77:8098",
                    "account_name",
                    "fallbackname")));

    assertThat(mismatching.publishReady()).isFalse();
    assertThat(mismatching.readinessPhase()).isEqualTo("WAITING_FOR_ACCOUNT");
  }

  @Test
  @DisplayName(
      "Micro6 execute task identity rejects a prefix collision (FISHING vs FISHING_TRAINING)")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_prefixCollisionDoesNotMatch() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "FISHING"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:FISHING_TRAINING", Map.of("task_key", "FISHING_TRAINING"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
  }

  @Test
  @DisplayName(
      "Micro6 execute task identity rejects a suffix collision (FISHING_TRAINING vs FISHING)")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_suffixCollisionDoesNotMatch() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "FISHING_TRAINING"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task("micro6:FISHING", Map.of("task_key", "FISHING"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
  }

  @Test
  @DisplayName("Micro6 execute task identity rejects a case-only mismatch")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_caseOnlyMismatchDoesNotMatch() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "fishing_training"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:FISHING_TRAINING", Map.of("task_key", "FISHING_TRAINING"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
  }

  @Test
  @DisplayName("Micro6 execute task identity rejects a whitespace mismatch")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_whitespaceMismatchDoesNotMatch() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "FISHING_TRAINING "));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:FISHING_TRAINING", Map.of("task_key", "FISHING_TRAINING"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
  }

  @Test
  @DisplayName("Micro6 execute task identity accepts an exact normalized task_key match")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_exactNormalizedTaskKeyMatches() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "FISHING_TRAINING"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:FISHING_TRAINING", Map.of("task_key", "FISHING_TRAINING"));

    assertThat(result.published()).isTrue();
    assertThat(result.publishStatus()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName(
      "Micro6 execute accepts a display name that resolves to the canonical task key via the catalog")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_displayNameResolvesToCanonicalTaskKey() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    // The executor returns a display name whose normalization differs from the task key.
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "Woodcutting"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService serviceWithResolver =
        new ScriptExecutionService(
            stateHandler,
            taskDetectionService,
            new ObjectMapper(),
            "micro6_local_executor",
            "http://127.0.0.1:8098",
            httpClient,
            0L,
            (Function<String, String>)
                candidate ->
                    "configurable_woodcutting".equals(candidate) || "Woodcutting".equals(candidate)
                        ? "configurable_woodcutting"
                        : null);

    ScriptExecutionService.Micro6PublishResult result =
        serviceWithResolver.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isTrue();
    assertThat(result.publishStatus()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName(
      "Micro6 execute rejects a display name that does not resolve through the catalog resolver")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_unresolvableDisplayNameDoesNotMatch() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(executeResponse.body()).thenReturn(acceptedBody(1001, "Woodcutting"));
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService serviceWithNullResolver =
        new ScriptExecutionService(
            stateHandler,
            taskDetectionService,
            new ObjectMapper(),
            "micro6_local_executor",
            "http://127.0.0.1:8098",
            httpClient,
            0L,
            (Function<String, String>) candidate -> null);

    ScriptExecutionService.Micro6PublishResult result =
        serviceWithNullResolver.publishMicro6Task(
            "micro6:configurable_woodcutting", Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isFalse();
    assertThat(result.publishStatus()).isEqualTo("RECONCILING");
  }

  private String bodyOf(HttpRequest request) throws Exception {
    HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    CountDownLatch completed = new CountDownLatch(1);
    publisher.subscribe(
        new Flow.Subscriber<ByteBuffer>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            output.write(bytes, 0, bytes.length);
          }

          @Override
          public void onError(Throwable throwable) {
            completed.countDown();
          }

          @Override
          public void onComplete() {
            completed.countDown();
          }
        });
    assertThat(completed.await(1, TimeUnit.SECONDS)).isTrue();
    return output.toString(StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("Micro6 publish fails closed on untrusted selected target without dialing")
  void publishMicro6Task_failsClosedOnUntrustedSelectedTargetWithoutDialing() throws Exception {
    Map<String, Object> taskMetadata =
        Map.of(
            "task_key",
            "configurable_woodcutting",
            "micro6_target",
            Map.of("advertised_base_url", "http://203.0.113.7:8098"));

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task("micro6:configurable_woodcutting", taskMetadata, 1001);

    assertThat(result.published()).isFalse();
    assertThat(result.errorMessage()).contains("ENDPOINT_UNTRUSTED");
    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  @DisplayName("Micro6 status lookup never dials an untrusted selected target")
  void getMicro6RunStatus_neverDialsUntrustedSelectedTarget() throws Exception {
    Map<String, Object> taskMetadata =
        Map.of(
            "task_key",
            "configurable_woodcutting",
            "micro6_target",
            Map.of("advertised_base_url", "http://user:pass@127.0.0.1:8098"));

    ScriptExecutionService.Micro6RunStatusResult result =
        service.getMicro6RunStatus(taskMetadata, 1337);

    assertThat(result.found()).isFalse();
    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  void productionAuthorityPreventsLegacyDefaultRouteFallback() throws Exception {
    var authority =
        org.mockito.Mockito.mock(
            com.chromascape.web.debug.buildergoal.service.BuilderGoalEndpointAuthority.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "endpointAuthority", authority);
    var result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting",
            Map.of("task_key", "configurable_woodcutting"),
            1001);
    assertThat(result.published()).isFalse();
    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  @DisplayName("Trusted endpoint verdict is exposed for callers")
  void trustedEndpointVerdictIsExposedForCallers() {
    assertThat(service.trustedEndpointVerdict("http://127.0.0.1:8098").trusted()).isTrue();
    assertThat(service.trustedEndpointVerdict("http://192.168.1.6:8098").trusted()).isFalse();
    assertThat(service.trustedEndpointVerdict("http://203.0.113.7:8098").trusted()).isFalse();
  }
}
