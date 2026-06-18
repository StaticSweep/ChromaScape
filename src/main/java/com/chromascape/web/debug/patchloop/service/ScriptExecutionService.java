package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.instance.RunConfig;
import com.chromascape.web.instance.ScriptInstance;
import com.chromascape.web.instance.ScriptInstanceManager;
import com.chromascape.web.instance.WebSocketStateHandler;
import com.chromascape.web.recording.service.TaskDetectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for executing scripts and tracking their completion.
 *
 * <p>Wraps the ScriptInstanceManager to provide script execution with polling and result tracking.
 */
@Service
public class ScriptExecutionService {

  private static final Logger logger = LoggerFactory.getLogger(ScriptExecutionService.class);
  private static final String BACKEND_LOCAL_INSTANCE = "local_instance";
  private static final String BACKEND_MICRO6_LOCAL_EXECUTOR = "micro6_local_executor";
  private static final String SCRIPT_SOURCE_PREFIX_MICRO6 = "micro6:";
  private static final String SCRIPT_SOURCE_PREFIX_LOCAL = "local:";

  private final ScriptInstanceManager instanceManager;
  private final WebSocketStateHandler stateHandler;
  private final TaskDetectionService taskDetectionService;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String executionBackend;
  private final String micro6ExecutorBaseUrl;
  private final AtomicInteger micro6EntryIdSeq = new AtomicInteger(1000);
  private final Map<Integer, String> micro6EntryToScript = new ConcurrentHashMap<>();
  private final AtomicReference<Integer> activeMicro6EntryId = new AtomicReference<>();
  private final AtomicReference<String> activeExecutionBackend = new AtomicReference<>();

  /** Tracks active runs keyed by executionId for execution-scoped control. */
  private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

  /** Metadata for an in-flight script run, bound to an executionId. */
  static final class ActiveRun {
    final String backend;
    final Integer micro6EntryId;
    final ScriptInstance localInstance;

    ActiveRun(String backend, Integer micro6EntryId, ScriptInstance localInstance) {
      this.backend = backend;
      this.micro6EntryId = micro6EntryId;
      this.localInstance = localInstance;
    }
  }

  @Autowired
  public ScriptExecutionService(
      WebSocketStateHandler stateHandler,
      TaskDetectionService taskDetectionService,
      ObjectMapper objectMapper,
      @Value("${chromascape.patchloop.execution-backend:micro6_local_executor}")
          String executionBackend,
      @Value("${chromascape.patchloop.micro6-executor.base-url:http://127.0.0.1:8098}")
          String micro6ExecutorBaseUrl) {
    this(
        stateHandler,
        taskDetectionService,
        objectMapper,
        executionBackend,
        micro6ExecutorBaseUrl,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
  }

  ScriptExecutionService(
      WebSocketStateHandler stateHandler,
      TaskDetectionService taskDetectionService,
      ObjectMapper objectMapper,
      String executionBackend,
      String micro6ExecutorBaseUrl,
      HttpClient httpClient) {
    this.instanceManager = ScriptInstanceManager.getInstance();
    this.stateHandler = stateHandler;
    this.taskDetectionService = taskDetectionService;
    this.objectMapper = objectMapper;
    this.executionBackend = executionBackend;
    this.micro6ExecutorBaseUrl = micro6ExecutorBaseUrl;
    this.httpClient = httpClient;
  }

  /** Result of a script execution. */
  public record ScriptExecutionResult(
      boolean success,
      String backend,
      String terminalStatus,
      boolean timedOut,
      String errorMessage,
      long durationMs) {

    public static ScriptExecutionResult success(
        String backend, String terminalStatus, long durationMs) {
      return new ScriptExecutionResult(true, backend, terminalStatus, false, null, durationMs);
    }

    public static ScriptExecutionResult failure(
        String backend, String terminalStatus, String errorMessage) {
      return new ScriptExecutionResult(false, backend, terminalStatus, false, errorMessage, 0);
    }

    public static ScriptExecutionResult timeout(String backend) {
      return new ScriptExecutionResult(
          false, backend, "TIMEOUT", true, "Script execution timed out", 0);
    }
  }

  /**
   * Runs a script and waits for completion (backward-compatible overload, unscoped).
   *
   * @param scriptName the name of the script to run
   * @param timeoutMs the maximum time to wait in milliseconds
   * @return the execution result
   */
  public ScriptExecutionResult runScriptAndWait(String scriptName, long timeoutMs) {
    return runScriptAndWait(null, scriptName, timeoutMs, null);
  }

  /**
   * Runs a script bound to a specific executionId and waits for completion.
   *
   * @param executionId the graph execution ID for scoped control (null for unscoped)
   * @param scriptName the name of the script to run
   * @param timeoutMs the maximum time to wait in milliseconds
   * @return the execution result
   */
  public ScriptExecutionResult runScriptAndWait(
      String executionId, String scriptName, long timeoutMs) {
    return runScriptAndWait(executionId, scriptName, timeoutMs, null);
  }

  public ScriptExecutionResult runScriptAndWait(
      String executionId,
      String scriptName,
      long timeoutMs,
      Map<String, Object> taskMetadata) {
    String normalizedScript = normalizeScriptName(scriptName);
    String selectedBackend = resolveBackend(scriptName);
    activeExecutionBackend.set(selectedBackend);
    try {
      if (BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(selectedBackend)) {
        return runViaMicro6LocalExecutor(executionId, normalizedScript, timeoutMs, taskMetadata);
      }
      if (!BACKEND_LOCAL_INSTANCE.equalsIgnoreCase(selectedBackend)) {
        logger.warn(
            "Unknown execution backend '{}', falling back to {}",
            selectedBackend,
            BACKEND_LOCAL_INSTANCE);
      }
      return runViaLocalInstance(executionId, normalizedScript, timeoutMs);
    } finally {
      activeExecutionBackend.compareAndSet(selectedBackend, null);
    }
  }

  private ScriptExecutionResult runViaLocalInstance(
      String executionId, String scriptName, long timeoutMs) {
    logger.info("Starting script execution: {}", scriptName);

    long startTime = System.currentTimeMillis();
    CountDownLatch completionLatch = new CountDownLatch(1);
    AtomicBoolean scriptSuccess = new AtomicBoolean(true);
    AtomicReference<String> errorMessage = new AtomicReference<>();

    try {
      // Create RunConfig and start the script
      RunConfig runConfig = new RunConfig(scriptName);
      ScriptInstance instance = new ScriptInstance(runConfig, stateHandler, taskDetectionService);
      instanceManager.setInstance(instance);
      if (executionId != null) {
        activeRuns.put(executionId, new ActiveRun(BACKEND_LOCAL_INSTANCE, null, instance));
      }

      // Create a custom thread wrapper to track completion
      Thread executionThread =
          new Thread(
              () -> {
                try {
                  instance.start();
                  // Wait for the script thread to complete
                  Thread scriptThread = getScriptThread(instance);
                  if (scriptThread != null) {
                    scriptThread.join();
                  }
                } catch (Exception e) {
                  logger.error("Script execution error", e);
                  scriptSuccess.set(false);
                  errorMessage.set(e.getMessage());
                } finally {
                  completionLatch.countDown();
                }
              });

      executionThread.start();

      // Wait for completion or timeout
      boolean completed = completionLatch.await(timeoutMs, TimeUnit.MILLISECONDS);

      if (!completed) {
        logger.warn("Script execution timed out after {}ms", timeoutMs);
        // Try to stop the script
        try {
          instance.stop();
        } catch (Exception e) {
          logger.error("Error stopping timed-out script", e);
        }
        return ScriptExecutionResult.timeout(BACKEND_LOCAL_INSTANCE);
      }

      long durationMs = System.currentTimeMillis() - startTime;

      if (scriptSuccess.get()) {
        logger.info("Script completed successfully in {}ms", durationMs);
        return ScriptExecutionResult.success(BACKEND_LOCAL_INSTANCE, "COMPLETED", durationMs);
      } else {
        logger.warn("Script failed: {}", errorMessage.get());
        return ScriptExecutionResult.failure(BACKEND_LOCAL_INSTANCE, "FAILED", errorMessage.get());
      }

    } catch (Exception e) {
      logger.error("Failed to start script: {}", scriptName, e);
      return ScriptExecutionResult.failure(BACKEND_LOCAL_INSTANCE, "FAILED", e.getMessage());
    } finally {
      if (executionId != null) {
        activeRuns.remove(executionId);
      }
    }
  }

  private ScriptExecutionResult runViaMicro6LocalExecutor(
      String executionId,
      String scriptName,
      long timeoutMs,
      Map<String, Object> taskMetadata) {
    final long startTime = System.currentTimeMillis();
    final int entryId = micro6EntryIdSeq.incrementAndGet();
    int trackedEntryId = entryId;
    micro6EntryToScript.put(entryId, scriptName);
    activeMicro6EntryId.set(entryId);

    if (executionId != null) {
      activeRuns.put(executionId, new ActiveRun(BACKEND_MICRO6_LOCAL_EXECUTOR, entryId, null));
    }

    try {
      String taskKey = resolveMicro6TaskKey(scriptName, taskMetadata);
      if (taskKey == null || taskKey.isBlank()) {
        return ScriptExecutionResult.failure(
            BACKEND_MICRO6_LOCAL_EXECUTOR,
            "FAILED",
            "Missing Micro6 task identity: taskMetadata.task_key, taskMetadata.task_name, or scriptName is required");
      }

      Map<String, Object> mergedTaskMetadata = new LinkedHashMap<>();
      mergedTaskMetadata.put("display_name", scriptName);
      mergedTaskMetadata.put("task_name", scriptName);
      mergedTaskMetadata.put("task_key", taskKey);
      if (taskMetadata != null && !taskMetadata.isEmpty()) {
        mergedTaskMetadata.putAll(taskMetadata);
      }

      String requestBody =
          objectMapper.writeValueAsString(
              Map.of(
                  "task_key", taskKey,
                  "entry_id", entryId,
                  "timeout_ms", timeoutMs,
                  "task_metadata", mergedTaskMetadata));

      HttpRequest executeRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(micro6ExecutorBaseUrl + "/api/v1/execute"))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      HttpResponse<String> executeResponse =
          httpClient.send(executeRequest, HttpResponse.BodyHandlers.ofString());

      if (executeResponse.statusCode() >= 400) {
        Integer existingEntryId = tryResolveAlreadyQueuedEntry(executeResponse);
        if (existingEntryId != null) {
          trackedEntryId = existingEntryId;
          micro6EntryToScript.put(trackedEntryId, scriptName);
          activeMicro6EntryId.set(trackedEntryId);
          if (executionId != null) {
            activeRuns.put(
                executionId, new ActiveRun(BACKEND_MICRO6_LOCAL_EXECUTOR, trackedEntryId, null));
          }
          logger.info(
              "Attached to existing Micro6 run for task {} (existing_entry_id={})",
              scriptName,
              trackedEntryId);
        } else {
          return ScriptExecutionResult.failure(
              BACKEND_MICRO6_LOCAL_EXECUTOR,
              "FAILED",
              "Micro6 executor rejected task (HTTP "
                  + executeResponse.statusCode()
                  + "): "
                  + executeResponse.body());
        }
      }

      long deadline = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() <= deadline) {
        HttpResponse<String> statusResponse = queryMicro6Status(trackedEntryId);

        if (statusResponse.statusCode() == 200) {
          JsonNode statusJson = objectMapper.readTree(statusResponse.body());
          String status = statusJson.path("status").asText("UNKNOWN");
          if ("COMPLETED".equalsIgnoreCase(status)) {
            return ScriptExecutionResult.success(
                BACKEND_MICRO6_LOCAL_EXECUTOR, "COMPLETED", System.currentTimeMillis() - startTime);
          }
          if ("FAILED".equalsIgnoreCase(status)) {
            return ScriptExecutionResult.failure(
                BACKEND_MICRO6_LOCAL_EXECUTOR,
                "FAILED",
                statusJson.path("error_message").asText("Micro6 task failed"));
          }
          if ("CANCELLED".equalsIgnoreCase(status)) {
            return ScriptExecutionResult.failure(
                BACKEND_MICRO6_LOCAL_EXECUTOR,
                "CANCELLED",
                statusJson.path("error_message").asText("Micro6 task was cancelled"));
          }
        } else if (statusResponse.statusCode() >= 400 && statusResponse.statusCode() != 404) {
          return ScriptExecutionResult.failure(
              BACKEND_MICRO6_LOCAL_EXECUTOR,
              "FAILED",
              "Micro6 status polling failed (HTTP " + statusResponse.statusCode() + ")");
        }

        Thread.sleep(1000);
      }

      stopMicro6Run(trackedEntryId, "Timed out while waiting for completion");
      return ScriptExecutionResult.timeout(BACKEND_MICRO6_LOCAL_EXECUTOR);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ScriptExecutionResult.failure(
          BACKEND_MICRO6_LOCAL_EXECUTOR, "FAILED", "Interrupted while waiting for Micro6 executor");
    } catch (Exception e) {
      logger.error("Failed Micro6 executor run for script: {}", scriptName, e);
      return ScriptExecutionResult.failure(
          BACKEND_MICRO6_LOCAL_EXECUTOR, "FAILED", "Micro6 executor error: " + e.getMessage());
    } finally {
      micro6EntryToScript.remove(entryId);
      if (trackedEntryId != entryId) {
        micro6EntryToScript.remove(trackedEntryId);
      }
      activeMicro6EntryId.compareAndSet(trackedEntryId, null);
      activeMicro6EntryId.compareAndSet(entryId, null);
      if (executionId != null) {
        activeRuns.remove(executionId);
      }
    }
  }

  private Integer tryResolveAlreadyQueuedEntry(HttpResponse<String> executeResponse) {
    if (executeResponse == null || executeResponse.statusCode() != 409) {
      return null;
    }
    try {
      JsonNode body = objectMapper.readTree(executeResponse.body());
      String errorMessage = body.path("error_message").asText("");
      if (!"task_already_queued".equalsIgnoreCase(errorMessage)) {
        return null;
      }
      if (body.has("existing_entry_id") && body.get("existing_entry_id").canConvertToInt()) {
        int existingEntryId = body.get("existing_entry_id").asInt(-1);
        return existingEntryId > 0 ? existingEntryId : null;
      }
    } catch (Exception e) {
      logger.debug("Failed to parse Micro6 conflict response: {}", e.getMessage());
    }
    return null;
  }

  /** Gets the current script instance if running. */
  public ScriptInstance getCurrentInstance() {
    return instanceManager.getInstanceRef();
  }

  /**
   * Stops the currently running script (legacy compatibility wrapper).
   *
   * <p>Delegates to execution-scoped {@link #stopScript} when exactly one active run exists. If
   * multiple active runs exist, logs a warning and returns immediately to avoid mis-targeting.
   * Falls back to global micro6 stop when no active runs are tracked and micro6 backend is active,
   * then to local-instance stop.
   */
  public void stopCurrentScript() {
    SingleRunSelection selection = selectSingleActiveRun();
    if (selection.state == SingleRunSelectionState.SINGLE) {
      stopScript(selection.executionId, "Cancelled from patch loop");
      return;
    }
    if (selection.state == SingleRunSelectionState.MULTIPLE) {
      logger.warn(
          "stopCurrentScript called with multiple active runs; cannot safely pick one, skipping");
      return;
    }
    // Fallback: no active runs tracked — try legacy micro6 stop first, then local-instance stop
    if (BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(getActiveBackendOrDefault())) {
      Integer entryId = activeMicro6EntryId.get();
      if (entryId != null) {
        stopMicro6Run(entryId, "Cancelled from patch loop");
        return;
      }
    }
    ScriptInstance instance = instanceManager.getInstanceRef();
    if (instance != null) {
      instance.stop();
    }
  }

  /**
   * Pauses current script execution (legacy compatibility wrapper).
   *
   * <p>Delegates to execution-scoped {@link #pauseScript} when exactly one active run exists. If
   * multiple active runs exist, logs a warning and returns immediately to avoid mis-targeting.
   * Falls back to global activeMicro6EntryId when no active runs are tracked.
   */
  public void pauseCurrentScript() {
    SingleRunSelection selection = selectSingleActiveRun();
    if (selection.state == SingleRunSelectionState.SINGLE) {
      pauseScript(selection.executionId);
      return;
    }
    if (selection.state == SingleRunSelectionState.MULTIPLE) {
      logger.warn(
          "pauseCurrentScript called with multiple active runs; cannot safely pick one, skipping");
      return;
    }
    // Legacy path: fall back to global activeMicro6EntryId
    if (BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(getActiveBackendOrDefault())) {
      Integer entryId = activeMicro6EntryId.get();
      if (entryId != null) {
        controlMicro6Run(entryId, "pause");
      }
    }
  }

  /**
   * Resumes current script execution (legacy compatibility wrapper).
   *
   * <p>Delegates to execution-scoped {@link #resumeScript} when exactly one active run exists. If
   * multiple active runs exist, logs a warning and returns immediately to avoid mis-targeting.
   * Falls back to global activeMicro6EntryId when no active runs are tracked.
   */
  public void resumeCurrentScript() {
    SingleRunSelection selection = selectSingleActiveRun();
    if (selection.state == SingleRunSelectionState.SINGLE) {
      resumeScript(selection.executionId);
      return;
    }
    if (selection.state == SingleRunSelectionState.MULTIPLE) {
      logger.warn(
          "resumeCurrentScript called with multiple active runs; cannot safely pick one, skipping");
      return;
    }
    // Legacy path: fall back to global activeMicro6EntryId
    if (BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(getActiveBackendOrDefault())) {
      Integer entryId = activeMicro6EntryId.get();
      if (entryId != null) {
        controlMicro6Run(entryId, "resume");
      }
    }
  }

  /** Pauses the script run bound to the given executionId. */
  public void pauseScript(String executionId) {
    ActiveRun run = executionId != null ? activeRuns.get(executionId) : null;
    if (run == null) {
      logger.warn("pauseScript: no active run for executionId={}, skipping", executionId);
      return;
    }
    if (BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(run.backend) && run.micro6EntryId != null) {
      controlMicro6Run(run.micro6EntryId, "pause");
    } else {
      logger.info(
          "pauseScript: pause not supported for backend={}, executionId={}",
          run.backend,
          executionId);
    }
  }

  /** Resumes the script run bound to the given executionId. */
  public void resumeScript(String executionId) {
    ActiveRun run = executionId != null ? activeRuns.get(executionId) : null;
    if (run == null) {
      logger.warn("resumeScript: no active run for executionId={}, skipping", executionId);
      return;
    }
    if (BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(run.backend) && run.micro6EntryId != null) {
      controlMicro6Run(run.micro6EntryId, "resume");
    } else {
      logger.info(
          "resumeScript: resume not supported for backend={}, executionId={}",
          run.backend,
          executionId);
    }
  }

  /** Stops the script run bound to the given executionId. */
  public void stopScript(String executionId, String reason) {
    ActiveRun run = executionId != null ? activeRuns.get(executionId) : null;
    if (run == null) {
      logger.warn("stopScript: no active run for executionId={}, skipping", executionId);
      return;
    }
    if (BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(run.backend) && run.micro6EntryId != null) {
      stopMicro6Run(run.micro6EntryId, reason);
    } else if (run.localInstance != null) {
      try {
        run.localInstance.stop();
      } catch (Exception e) {
        logger.warn(
            "stopScript: error stopping local instance for executionId={}: {}",
            executionId,
            e.getMessage());
      }
    }
  }

  /** Checks if a script is currently running. */
  public boolean isScriptRunning() {
    ScriptInstance instance = instanceManager.getInstanceRef();
    if (instance == null) {
      return false;
    }

    // Check if the thread is still alive
    Thread scriptThread = getScriptThread(instance);
    return scriptThread != null && scriptThread.isAlive();
  }

  /**
   * Tries to get the internal thread from the script instance. This uses reflection to access the
   * private thread field.
   */
  private Thread getScriptThread(ScriptInstance instance) {
    try {
      java.lang.reflect.Field threadField = ScriptInstance.class.getDeclaredField("thread");
      threadField.setAccessible(true);
      return (Thread) threadField.get(instance);
    } catch (Exception e) {
      logger.debug("Could not access script thread field", e);
      return null;
    }
  }

  private String resolveBackend(String scriptName) {
    if (scriptName == null) {
      return executionBackend;
    }
    if (scriptName.startsWith(SCRIPT_SOURCE_PREFIX_MICRO6)) {
      return BACKEND_MICRO6_LOCAL_EXECUTOR;
    }
    if (scriptName.startsWith(SCRIPT_SOURCE_PREFIX_LOCAL)) {
      return BACKEND_LOCAL_INSTANCE;
    }
    return executionBackend;
  }

  private String normalizeScriptName(String scriptName) {
    if (scriptName == null) {
      return null;
    }
    if (scriptName.startsWith(SCRIPT_SOURCE_PREFIX_MICRO6)) {
      return scriptName.substring(SCRIPT_SOURCE_PREFIX_MICRO6.length());
    }
    if (scriptName.startsWith(SCRIPT_SOURCE_PREFIX_LOCAL)) {
      return scriptName.substring(SCRIPT_SOURCE_PREFIX_LOCAL.length());
    }
    return scriptName;
  }

  private String resolveMicro6TaskKey(String scriptName, Map<String, Object> taskMetadata) {
    String metadataTaskKey = stringMetadata(taskMetadata, "task_key");
    if (metadataTaskKey != null) {
      return metadataTaskKey;
    }

    String metadataTaskName = stringMetadata(taskMetadata, "task_name");
    if (metadataTaskName != null) {
      return metadataTaskName;
    }

    String normalizedScriptName = normalizeScriptName(scriptName);
    return normalizedScriptName == null || normalizedScriptName.isBlank()
        ? null
        : normalizedScriptName;
  }

  private String stringMetadata(Map<String, Object> taskMetadata, String key) {
    if (taskMetadata == null) {
      return null;
    }
    Object value = taskMetadata.get(key);
    if (value == null) {
      return null;
    }
    String text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private HttpResponse<String> queryMicro6Status(int entryId) throws Exception {
    HttpRequest statusRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(micro6ExecutorBaseUrl + "/api/v1/runs/" + entryId + "/status"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      HttpRequest fallback =
          HttpRequest.newBuilder()
              .uri(URI.create(micro6ExecutorBaseUrl + "/api/v1/status/" + entryId))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();
      return httpClient.send(fallback, HttpResponse.BodyHandlers.ofString());
    }
    return response;
  }

  private void stopMicro6Run(int entryId, String reason) {
    controlMicro6Run(entryId, "stop", reason);
  }

  private void controlMicro6Run(int entryId, String action) {
    controlMicro6Run(entryId, action, null);
  }

  private void controlMicro6Run(int entryId, String action, String reason) {
    try {
      String body =
          reason == null || reason.isBlank()
              ? "{}"
              : objectMapper.writeValueAsString(Map.of("reason", reason));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(micro6ExecutorBaseUrl + "/api/v1/runs/" + entryId + "/" + action))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        logger.warn(
            "Micro6 run control '{}' failed for entry {} (HTTP {}): {}",
            action,
            entryId,
            response.statusCode(),
            response.body());
      }
    } catch (Exception e) {
      logger.warn(
          "Micro6 run control '{}' failed for entry {}: {}", action, entryId, e.getMessage());
    }
  }

  private String getActiveBackendOrDefault() {
    String active = activeExecutionBackend.get();
    return active != null ? active : executionBackend;
  }

  private SingleRunSelection selectSingleActiveRun() {
    Iterator<String> iterator = activeRuns.keySet().iterator();
    if (!iterator.hasNext()) {
      return new SingleRunSelection(SingleRunSelectionState.NONE, null);
    }
    String first = iterator.next();
    if (iterator.hasNext()) {
      return new SingleRunSelection(SingleRunSelectionState.MULTIPLE, null);
    }
    return new SingleRunSelection(SingleRunSelectionState.SINGLE, first);
  }

  private enum SingleRunSelectionState {
    NONE,
    SINGLE,
    MULTIPLE
  }

  private static final class SingleRunSelection {
    final SingleRunSelectionState state;
    final String executionId;

    SingleRunSelection(SingleRunSelectionState state, String executionId) {
      this.state = state;
      this.executionId = executionId;
    }
  }
}
