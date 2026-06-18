package com.chromascape.web.debug.patchloop.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.instance.WebSocketStateHandler;
import com.chromascape.web.recording.service.TaskDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("MyScript", 1000);

    assertThat(result.success()).isTrue();
    assertThat(result.backend()).isEqualTo("micro6_local_executor");
    assertThat(result.terminalStatus()).isEqualTo("COMPLETED");
    assertThat(result.timedOut()).isFalse();
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  @DisplayName("Micro6 backend prefers taskMetadata.task_key over task_name and scriptName")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6PrefersTaskKey() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"COMPLETED\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait(
            null,
            "Display Script",
            1000,
            Map.of(
                "task_key", "CANONICAL_TASK",
                "task_name", "LEGACY_TASK",
                "display_name", "Display Name"));

    assertThat(result.success()).isTrue();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(2)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    String requestBody = extractRequestBody(captor.getAllValues().get(0));
    assertThat(requestBody).contains("\"task_key\":\"CANONICAL_TASK\"");
    assertThat(requestBody).contains("\"task_name\":\"LEGACY_TASK\"");
    assertThat(requestBody).contains("\"display_name\":\"Display Name\"");
  }

  @Test
  @DisplayName("Micro6 backend falls back to taskMetadata.task_name when task_key is absent")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6FallsBackToTaskName() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"COMPLETED\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait(null, "Display Script", 1000, Map.of("task_name", "LEGACY_TASK"));

    assertThat(result.success()).isTrue();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(2)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    String requestBody = extractRequestBody(captor.getAllValues().get(0));
    assertThat(requestBody).contains("\"task_key\":\"LEGACY_TASK\"");
  }

  @Test
  @DisplayName("Micro6 backend falls back to normalized scriptName when metadata is absent")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6FallsBackToNormalizedScriptName() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"COMPLETED\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("micro6:QuestTask", 1000);

    assertThat(result.success()).isTrue();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(2)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    String requestBody = extractRequestBody(captor.getAllValues().get(0));
    assertThat(requestBody).contains("\"task_key\":\"QuestTask\"");
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
  @DisplayName(
      "Micro6 backend attaches to existing queued run when conflict includes existing_entry_id")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6AttachOnAlreadyQueuedConflict() throws Exception {
    when(executeResponse.statusCode()).thenReturn(409);
    when(executeResponse.body())
        .thenReturn(
            "{\"success\":false,\"error_message\":\"task_already_queued\",\"existing_entry_id\":4321}");
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body()).thenReturn("{\"status\":\"COMPLETED\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("MyScript", 1000);

    assertThat(result.success()).isTrue();
    assertThat(result.terminalStatus()).isEqualTo("COMPLETED");

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(2)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    List<HttpRequest> requests = captor.getAllValues();
    assertThat(requests.get(0).uri().getPath()).isEqualTo("/api/v1/execute");
    assertThat(requests.get(1).uri().getPath()).isEqualTo("/api/v1/runs/4321/status");
  }

  @Test
  @DisplayName("Micro6 backend returns failure when queued conflict has no attachable entry id")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6AlreadyQueuedConflictWithoutEntryId() throws Exception {
    when(executeResponse.statusCode()).thenReturn(409);
    when(executeResponse.body()).thenReturn("{\"error_message\":\"task_already_queued\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("MyScript", 1000);

    assertThat(result.success()).isFalse();
    assertThat(result.terminalStatus()).isEqualTo("FAILED");
    assertThat(result.errorMessage()).contains("HTTP 409");
  }

  @Test
  @DisplayName("Micro6 backend returns CANCELLED terminal status")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6Cancelled() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);
    when(statusResponse.statusCode()).thenReturn(200);
    when(statusResponse.body())
        .thenReturn("{\"status\":\"CANCELLED\",\"error_message\":\"user_stop\"}");
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, statusResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("micro6:QuestTask", 1000);

    assertThat(result.success()).isFalse();
    assertThat(result.backend()).isEqualTo("micro6_local_executor");
    assertThat(result.terminalStatus()).isEqualTo("CANCELLED");
    assertThat(result.errorMessage()).isEqualTo("user_stop");
  }

  @Test
  @DisplayName("Micro6 backend falls back to alternate status endpoint on 404")
  @SuppressWarnings("unchecked")
  void runScriptAndWait_micro6StatusFallbackOn404() throws Exception {
    when(executeResponse.statusCode()).thenReturn(200);

    // First status call returns 404, triggering fallback
    HttpResponse<String> notFoundResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(notFoundResponse.statusCode()).thenReturn(404);

    // Fallback status call returns COMPLETED
    HttpResponse<String> fallbackResponse = org.mockito.Mockito.mock(HttpResponse.class);
    when(fallbackResponse.statusCode()).thenReturn(200);
    when(fallbackResponse.body()).thenReturn("{\"status\":\"COMPLETED\"}");

    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(executeResponse, notFoundResponse, fallbackResponse);

    ScriptExecutionService.ScriptExecutionResult result =
        service.runScriptAndWait("MyScript", 10000);

    assertThat(result.success()).isTrue();
    assertThat(result.terminalStatus()).isEqualTo("COMPLETED");

    // Verify the exact URI sequence: execute -> runs/{id}/status (404) -> status/{id} (fallback)
    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(3)).send(captor.capture(), any(HttpResponse.BodyHandler.class));

    List<HttpRequest> requests = captor.getAllValues();
    assertThat(requests.get(0).uri().getPath()).isEqualTo("/api/v1/execute");
    assertThat(requests.get(1).uri().getPath()).matches("/api/v1/runs/\\d+/status");
    assertThat(requests.get(2).uri().getPath()).matches("/api/v1/status/\\d+");
  }

  // --- Execution-scoped control tests (pauseScript/resumeScript/stopScript) ---

  @Test
  @DisplayName("pauseScript with executionId targets correct micro6 entry")
  @SuppressWarnings("unchecked")
  void pauseScript_targetsCorrectEntry() throws Exception {
    int entryId = 1234;
    registerActiveRun("exec_pause_test", entryId);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.pauseScript("exec_pause_test");

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/1234/pause");
  }

  @Test
  @DisplayName("resumeScript with executionId targets correct micro6 entry")
  @SuppressWarnings("unchecked")
  void resumeScript_targetsCorrectEntry() throws Exception {
    int entryId = 1234;
    registerActiveRun("exec_resume_test", entryId);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.resumeScript("exec_resume_test");

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/1234/resume");
  }

  @Test
  @DisplayName("stopScript with executionId stops the correct run and includes reason")
  @SuppressWarnings("unchecked")
  void stopScript_targetsCorrectRun() throws Exception {
    int entryId = 1234;
    registerActiveRun("exec_stop_test", entryId);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.stopScript("exec_stop_test", "test reason");

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/1234/stop");

    String requestBody = extractRequestBody(captor.getValue());
    assertThat(requestBody).contains("test reason");
  }

  @Test
  @DisplayName("pauseScript logs warning and skips when no active run for executionId")
  @SuppressWarnings("unchecked")
  void pauseScript_noopWhenNoActiveRun() throws Exception {
    // No run started, so pauseScript should just log and return without sending any HTTP request
    service.pauseScript("nonexistent_exec_id");

    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  // --- Legacy wrapper branch tests (stopCurrentScript/pauseCurrentScript/resumeCurrentScript) ---

  @Test
  @DisplayName("stopCurrentScript delegates to stopScript when exactly one active run exists")
  @SuppressWarnings("unchecked")
  void stopCurrentScript_delegatesWhenSingleActiveRun() throws Exception {
    registerActiveRun("exec_single", 5555);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.stopCurrentScript();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/5555/stop");
  }

  @Test
  @DisplayName("pauseCurrentScript delegates to pauseScript when exactly one active run exists")
  @SuppressWarnings("unchecked")
  void pauseCurrentScript_delegatesWhenSingleActiveRun() throws Exception {
    registerActiveRun("exec_single", 5555);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.pauseCurrentScript();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/5555/pause");
  }

  @Test
  @DisplayName("resumeCurrentScript delegates to resumeScript when exactly one active run exists")
  @SuppressWarnings("unchecked")
  void resumeCurrentScript_delegatesWhenSingleActiveRun() throws Exception {
    registerActiveRun("exec_single", 5555);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.resumeCurrentScript();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/5555/resume");
  }

  @Test
  @DisplayName("stopCurrentScript skips when multiple active runs exist")
  @SuppressWarnings("unchecked")
  void stopCurrentScript_noopWhenMultipleActiveRuns() throws Exception {
    registerActiveRun("exec_a", 1001);
    registerActiveRun("exec_b", 1002);

    service.stopCurrentScript();

    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  @DisplayName("pauseCurrentScript skips when multiple active runs exist")
  @SuppressWarnings("unchecked")
  void pauseCurrentScript_noopWhenMultipleActiveRuns() throws Exception {
    registerActiveRun("exec_a", 1001);
    registerActiveRun("exec_b", 1002);

    service.pauseCurrentScript();

    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  @DisplayName("resumeCurrentScript skips when multiple active runs exist")
  @SuppressWarnings("unchecked")
  void resumeCurrentScript_noopWhenMultipleActiveRuns() throws Exception {
    registerActiveRun("exec_a", 1001);
    registerActiveRun("exec_b", 1002);

    service.resumeCurrentScript();

    verify(httpClient, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
  }

  @Test
  @DisplayName(
      "stopCurrentScript falls back to micro6 global stop when zero active runs and activeMicro6EntryId set")
  @SuppressWarnings("unchecked")
  void stopCurrentScript_micro6LegacyFallbackWhenZeroRuns() throws Exception {
    registerActiveMicro6EntryId(7777);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.stopCurrentScript();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/7777/stop");
  }

  @Test
  @DisplayName(
      "pauseCurrentScript falls back to micro6 global pause when zero active runs and activeMicro6EntryId set")
  @SuppressWarnings("unchecked")
  void pauseCurrentScript_micro6LegacyFallbackWhenZeroRuns() throws Exception {
    registerActiveMicro6EntryId(7777);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.pauseCurrentScript();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/7777/pause");
  }

  @Test
  @DisplayName(
      "resumeCurrentScript falls back to micro6 global resume when zero active runs and activeMicro6EntryId set")
  @SuppressWarnings("unchecked")
  void resumeCurrentScript_micro6LegacyFallbackWhenZeroRuns() throws Exception {
    registerActiveMicro6EntryId(7777);

    when(statusResponse.statusCode()).thenReturn(200);
    when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
        .thenReturn(statusResponse);

    service.resumeCurrentScript();

    ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient, times(1)).send(captor.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(captor.getValue().uri().getPath()).isEqualTo("/api/v1/runs/7777/resume");
  }

  // --- Test helpers ---

  /** Directly injects an active run into the service's activeRuns map via reflection. */
  private void registerActiveRun(String executionId, int micro6EntryId) throws Exception {
    Field field = ScriptExecutionService.class.getDeclaredField("activeRuns");
    field.setAccessible(true);
    ConcurrentHashMap<String, ScriptExecutionService.ActiveRun> runs =
        (ConcurrentHashMap<String, ScriptExecutionService.ActiveRun>) field.get(service);
    runs.put(
        executionId,
        new ScriptExecutionService.ActiveRun("micro6_local_executor", micro6EntryId, null));
  }

  /** Directly sets the global activeMicro6EntryId via reflection for legacy fallback tests. */
  private void registerActiveMicro6EntryId(int entryId) throws Exception {
    Field field = ScriptExecutionService.class.getDeclaredField("activeMicro6EntryId");
    field.setAccessible(true);
    ((AtomicReference<Integer>) field.get(service)).set(entryId);
  }

  /**
   * Extracts the request body from an HttpRequest by synchronously collecting the BodyPublisher
   * output. Uses a CountDownLatch to wait for the publisher to complete before returning.
   */
  private String extractRequestBody(HttpRequest request) throws Exception {
    HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElse(null);
    if (publisher == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> publishError = new AtomicReference<>();
    Flow.Subscriber<ByteBuffer> subscriber =
        new Flow.Subscriber<>() {
          @Override
          public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
          }

          @Override
          public void onNext(ByteBuffer item) {
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            sb.append(new String(bytes, StandardCharsets.UTF_8));
          }

          @Override
          public void onError(Throwable throwable) {
            publishError.set(throwable);
            done.countDown();
          }

          @Override
          public void onComplete() {
            done.countDown();
          }
        };
    publisher.subscribe(subscriber);
    if (!done.await(5, TimeUnit.SECONDS)) {
      throw new AssertionError("BodyPublisher did not complete within timeout");
    }
    if (publishError.get() != null) {
      throw new AssertionError("BodyPublisher failed", publishError.get());
    }
    return sb.toString();
  }
}
