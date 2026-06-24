package com.chromascape.web.debug.patchloop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.instance.WebSocketStateHandler;
import com.chromascape.web.recording.service.TaskDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
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
            httpClient);
  }

  @Test
  @DisplayName("Micro6 backend returns COMPLETED terminal status")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6Completed() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"COMPLETED\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result = service.runScriptAndWait("MyScript", 1000);

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
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"FAILED\",\"error_message\":\"boom\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result = service.runScriptAndWait("MyScript", 1000);

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

    ScriptExecutionService.ScriptExecutionResult result = service.runScriptAndWait("MyScript", 1000);

    assertThat(result.success()).isFalse();
    assertThat(result.backend()).isEqualTo("micro6_local_executor");
    assertThat(result.terminalStatus()).isEqualTo("FAILED");
    assertThat(result.errorMessage()).contains("HTTP 503");
  }

  @Test
  @DisplayName("Micro6 backend dispatches to targeted advertised base URL when provided")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6UsesTargetBaseUrl() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"COMPLETED\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    Map<String, Object> taskMetadata =
        Map.of(
            "task_key",
            "configurable_woodcutting",
            "micro6_target",
            Map.of("advertised_base_url", "http://192.168.1.77:8098"));

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("execution-1", "micro6:configurable_woodcutting", 1000, taskMetadata);

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
  @DisplayName("Micro6 publish returns accepted entry id without waiting for completion")
  @SuppressWarnings("unchecked")
  void publishMicro6Task_returnsPublishedResult() throws Exception {
    when(executeResponse.statusCode()).thenReturn(202);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.Micro6PublishResult result =
        service.publishMicro6Task(
            "micro6:configurable_woodcutting",
            Map.of("task_key", "configurable_woodcutting"));

    assertThat(result.published()).isTrue();
    assertThat(result.publishStatus()).isEqualTo("PUBLISHED");
    assertThat(result.entryId()).isNotNull();
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  @DisplayName("Micro6 status polling resolves target-specific run status")
  @SuppressWarnings("unchecked")
  void getMicro6RunStatus_readsTargetedStatus() throws Exception {
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"RUNNING\",\"entry_id\":1337}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    ScriptExecutionService.Micro6RunStatusResult result =
        service.getMicro6RunStatus(
            Map.of(
                "micro6_target",
                Map.of("advertised_base_url", "http://192.168.1.55:8098")),
            1337);

    ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

    assertThat(requestCaptor.getValue().uri().toString())
        .isEqualTo("http://192.168.1.55:8098/api/v1/runs/1337/status");
    assertThat(result.found()).isTrue();
    assertThat(result.status()).isEqualTo("RUNNING");
  }
}
