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
import java.net.http.HttpTimeoutException;
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

  /** Timeout-recovery probe window after an ambiguous execute timeout. Injectable for tests. */
  private final long micro6TimeoutRecoveryProbeMs;

  /** Tracks active runs keyed by executionId for execution-scoped control. */
  private final ConcurrentHashMap<String, ActiveRun> activeRuns = new ConcurrentHashMap<>();

  /** Metadata for an in-flight script run, bound to an executionId. */
  static final class ActiveRun {
    final String backend;
    final Integer micro6EntryId;
    final ScriptInstance localInstance;
    final String micro6BaseUrl;

    ActiveRun(
        String backend, Integer micro6EntryId, ScriptInstance localInstance, String micro6BaseUrl) {
      this.backend = backend;
      this.micro6EntryId = micro6EntryId;
      this.localInstance = localInstance;
      this.micro6BaseUrl = micro6BaseUrl;
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
    this(
        stateHandler,
        taskDetectionService,
        objectMapper,
        executionBackend,
        micro6ExecutorBaseUrl,
        httpClient,
        15_000L);
  }

  ScriptExecutionService(
      WebSocketStateHandler stateHandler,
      TaskDetectionService taskDetectionService,
      ObjectMapper objectMapper,
      String executionBackend,
      String micro6ExecutorBaseUrl,
      HttpClient httpClient,
      long micro6TimeoutRecoveryProbeMs) {
    this.instanceManager = ScriptInstanceManager.getInstance();
    this.stateHandler = stateHandler;
    this.taskDetectionService = taskDetectionService;
    this.objectMapper = objectMapper;
    this.executionBackend = executionBackend;
    this.micro6ExecutorBaseUrl = micro6ExecutorBaseUrl;
    this.httpClient = httpClient;
    this.micro6TimeoutRecoveryProbeMs = Math.max(0L, micro6TimeoutRecoveryProbeMs);
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

    /**
     * Ambiguous publish outcome: the execute request timed out and the executor has not confirmed
     * acceptance. The run must be reconciled via status polling, not treated as failed.
     */
    public static ScriptExecutionResult reconciling(String backend, Integer entryId, String errorMessage) {
      String message =
          errorMessage != null && !errorMessage.isBlank()
              ? errorMessage
              : "Awaiting executor confirmation for entry " + entryId;
      return new ScriptExecutionResult(false, backend, "RECONCILING", false, message, 0);
    }
  }

  public record Micro6PublishResult(
      boolean published,
      String backend,
      String publishStatus,
      Integer entryId,
      boolean attachedToExisting,
      String micro6BaseUrl,
      String errorMessage) {

    public static Micro6PublishResult success(
        Integer entryId,
        boolean attachedToExisting,
        String micro6BaseUrl,
        String publishStatus) {
      return new Micro6PublishResult(
          true,
          BACKEND_MICRO6_LOCAL_EXECUTOR,
          publishStatus,
          entryId,
          attachedToExisting,
          micro6BaseUrl,
          null);
    }

    /**
     * Ambiguous outcome: the execute request timed out and the executor has not (yet) confirmed
     * whether it accepted the entry. The caller must reconcile via status polling rather than
     * treating this as a terminal publish failure.
     */
    public static Micro6PublishResult reconciling(Integer entryId, String micro6BaseUrl, String errorMessage) {
      return new Micro6PublishResult(
          false,
          BACKEND_MICRO6_LOCAL_EXECUTOR,
          "RECONCILING",
          entryId,
          false,
          micro6BaseUrl,
          errorMessage);
    }

    public static Micro6PublishResult failure(String errorMessage, String micro6BaseUrl) {
      return new Micro6PublishResult(
          false,
          BACKEND_MICRO6_LOCAL_EXECUTOR,
          "PUBLISH_FAILED",
          null,
          false,
          micro6BaseUrl,
          errorMessage);
    }
  }

  public record Micro6RunStatusResult(
      boolean found,
      String status,
      String errorMessage,
      JsonNode rawResponse,
      String micro6BaseUrl,
      Integer entryId) {}

  public record Micro6ExecutorHealthResult(
      boolean reachable,
      boolean publishReady,
      String readinessPhase,
      String readinessReason,
      String resolvedAccountName,
      String resolvedAccountHash,
      String requestedAccountName,
      String requestedAccountHash,
      Boolean dispatchHold,
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Integer loginAttempt,
      Integer loginAttemptMax,
      String loginTerminalReason,
      String micro6BaseUrl,
      JsonNode rawResponse) {}

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
        activeRuns.put(executionId, new ActiveRun(BACKEND_LOCAL_INSTANCE, null, instance, null));
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
    final String targetBaseUrl = resolveMicro6BaseUrl(taskMetadata);
    micro6EntryToScript.put(entryId, scriptName);
    activeMicro6EntryId.set(entryId);

    if (executionId != null) {
      activeRuns.put(
          executionId,
          new ActiveRun(BACKEND_MICRO6_LOCAL_EXECUTOR, entryId, null, targetBaseUrl));
    }

    try {
      Micro6PublishResult publishResult =
          publishMicro6TaskInternal(scriptName, taskMetadata, entryId, targetBaseUrl, timeoutMs);
      if (!publishResult.published()) {
        if ("RECONCILING".equalsIgnoreCase(publishResult.publishStatus())) {
          // Ambiguous execute outcome: do not mark the run failed. Reconcile in the background
          // and keep the run active; the caller polls status via getMicro6RunStatus.
          logger.info(
              "Micro6 publish for task {} is RECONCILING (entry {}); continuing in background",
              scriptName,
              publishResult.entryId());
          return ScriptExecutionResult.reconciling(
              BACKEND_MICRO6_LOCAL_EXECUTOR,
              publishResult.entryId(),
              publishResult.errorMessage());
        }
        return ScriptExecutionResult.failure(
            BACKEND_MICRO6_LOCAL_EXECUTOR, "FAILED", publishResult.errorMessage());
      }
      if (publishResult.entryId() != null) {
        trackedEntryId = publishResult.entryId();
        micro6EntryToScript.put(trackedEntryId, scriptName);
        activeMicro6EntryId.set(trackedEntryId);
        if (executionId != null) {
          activeRuns.put(
              executionId,
              new ActiveRun(BACKEND_MICRO6_LOCAL_EXECUTOR, trackedEntryId, null, targetBaseUrl));
        }
      }

      long deadline = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() <= deadline) {
        HttpResponse<String> statusResponse = queryMicro6Status(targetBaseUrl, trackedEntryId);

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

      stopMicro6Run(targetBaseUrl, trackedEntryId, "Timed out while waiting for completion");
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

  private Micro6PublishResult publishMicro6TaskInternal(
      String scriptName,
      Map<String, Object> taskMetadata,
      int entryId,
      String targetBaseUrl,
      long timeoutMs)
      throws Exception {
    String taskKey = resolveMicro6TaskKey(scriptName, taskMetadata);
    if (taskKey == null || taskKey.isBlank()) {
      return Micro6PublishResult.failure(
          "Missing Micro6 task identity: taskMetadata.task_key, taskMetadata.task_name, or scriptName is required",
          targetBaseUrl);
    }

    Map<String, Object> mergedTaskMetadata = new LinkedHashMap<>();
    mergedTaskMetadata.put("display_name", scriptName);
    mergedTaskMetadata.put("task_name", scriptName);
    mergedTaskMetadata.put("task_key", taskKey);
    if (taskMetadata != null && !taskMetadata.isEmpty()) {
      mergedTaskMetadata.putAll(taskMetadata);
    }

    Map<String, Object> executePayload = new LinkedHashMap<>();
    executePayload.put("task_key", taskKey);
    executePayload.put("entry_id", entryId);
    executePayload.put("timeout_ms", timeoutMs);
    executePayload.put("task_metadata", mergedTaskMetadata);

    Object initiatedBy = mergedTaskMetadata.get("initiated_by");
    Object inputOrigin = mergedTaskMetadata.get("input_origin");
    if (initiatedBy != null) {
      executePayload.put("initiated_by", initiatedBy);
    }
    if (inputOrigin != null) {
      executePayload.put("input_origin", inputOrigin);
    }

    String requestBody = objectMapper.writeValueAsString(executePayload);

    HttpRequest executeRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(targetBaseUrl + "/api/v1/execute"))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

    HttpResponse<String> executeResponse;
    try {
      executeResponse = httpClient.send(executeRequest, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException timeoutException) {
      logger.warn(
          "Micro6 publish request timed out for task {} on {}; probing executor status before retry",
          scriptName,
          targetBaseUrl);
      Micro6PublishResult recoveredPublish =
          recoverAcceptedPublishAfterTimeout(
              scriptName, taskKey, entryId, targetBaseUrl, timeoutException);
      if (recoveredPublish != null) {
        return recoveredPublish;
      }
      logger.warn(
          "Micro6 publish status recovery did not find entry {} for task {}; retrying once",
          entryId,
          scriptName);
      try {
        executeResponse = httpClient.send(executeRequest, HttpResponse.BodyHandlers.ofString());
      } catch (HttpTimeoutException retryTimeoutException) {
        Micro6PublishResult retryRecoveredPublish =
            recoverAcceptedPublishAfterTimeout(
                scriptName, taskKey, entryId, targetBaseUrl, retryTimeoutException);
        if (retryRecoveredPublish != null) {
          return retryRecoveredPublish;
        }
        // The outcome is genuinely ambiguous: the executor may still be doing expensive dispatch
        // work and will accept the entry late. Never surface this as a terminal publish failure;
        // the caller transitions to RECONCILING and polls status in the background.
        logger.warn(
            "Micro6 publish remains ambiguous for task {} on {} after retry; entering RECONCILING for entry {}",
            scriptName,
            targetBaseUrl,
            entryId);
        return Micro6PublishResult.reconciling(
            entryId,
            targetBaseUrl,
            "Micro6 execute request timed out twice; awaiting executor confirmation for entry "
                + entryId);
      }
    }

    if (executeResponse.statusCode() >= 400) {
      Integer existingEntryId = tryResolveAlreadyQueuedEntry(executeResponse);
      if (existingEntryId != null) {
        logger.info(
            "Attached to existing Micro6 run for task {} (existing_entry_id={})",
            scriptName,
            existingEntryId);
        return Micro6PublishResult.success(existingEntryId, true, targetBaseUrl, "PUBLISHED");
      }
      String parsedExecutorError = parseMicro6ExecuteError(executeResponse);
      return Micro6PublishResult.failure(
          "Micro6 executor rejected task (HTTP "
              + executeResponse.statusCode()
              + "): "
              + parsedExecutorError,
          targetBaseUrl);
    }

    return Micro6PublishResult.success(entryId, false, targetBaseUrl, "PUBLISHED");
  }

  private Micro6PublishResult recoverAcceptedPublishAfterTimeout(
      String scriptName,
      String taskKey,
      int entryId,
      String targetBaseUrl,
      HttpTimeoutException timeoutException) {
    long deadline = System.currentTimeMillis() + micro6TimeoutRecoveryProbeMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        Micro6RunStatusResult statusResult =
            getMicro6RunStatus(Map.of("local_executor_base_url", targetBaseUrl), entryId);
        if (isAcceptedPublishStatus(statusResult)) {
          logger.info(
              "Recovered timed out Micro6 publish for task {} on {} via status {} for entry {}",
              scriptName,
              targetBaseUrl,
              statusResult.status(),
              entryId);
          return Micro6PublishResult.success(entryId, false, targetBaseUrl, "PUBLISHED");
        }
      } catch (Exception statusException) {
        logger.debug(
            "Micro6 publish timeout recovery status poll failed for entry {}: {}",
            entryId,
            statusException.getMessage());
      }

      try {
        Micro6ExecutorHealthResult healthResult =
            getMicro6ExecutorHealth(Map.of("local_executor_base_url", targetBaseUrl));
        JsonNode rawHealth = healthResult.rawResponse();
        String currentTask = rawHealth == null ? null : textNode(rawHealth, "current_task");
        if (healthResult.reachable()
            && currentTask != null
            && matchesPublishedTask(currentTask, scriptName, taskKey)) {
          logger.info(
              "Recovered timed out Micro6 publish for task {} on {} via /health current_task='{}'",
              scriptName,
              targetBaseUrl,
              currentTask);
          return Micro6PublishResult.success(entryId, false, targetBaseUrl, "PUBLISHED");
        }
      } catch (Exception healthException) {
        logger.debug(
            "Micro6 publish timeout recovery health probe failed for entry {}: {}",
            entryId,
            healthException.getMessage());
      }

      try {
        Thread.sleep(1000L);
      } catch (InterruptedException interruptedException) {
        Thread.currentThread().interrupt();
        return Micro6PublishResult.failure(
            "Micro6 executor error: "
                + firstNonBlank(interruptedException.getMessage(), timeoutException.getMessage()),
            targetBaseUrl);
      }
    }
    return null;
  }

  private boolean isAcceptedPublishStatus(Micro6RunStatusResult statusResult) {
    if (statusResult == null || !statusResult.found()) {
      return false;
    }
    String status = statusResult.status();
    if (status == null || status.isBlank()) {
      return false;
    }
    return "PENDING".equalsIgnoreCase(status)
        || "QUEUED".equalsIgnoreCase(status)
        || "RUNNING".equalsIgnoreCase(status)
        || "COMPLETED".equalsIgnoreCase(status)
        || "ACCEPTED".equalsIgnoreCase(status);
  }

  private boolean matchesPublishedTask(String currentTask, String scriptName, String taskKey) {
    if (currentTask == null || currentTask.isBlank()) {
      return false;
    }
    String normalizedCurrentTask = normalizeTaskToken(currentTask);
    return normalizedCurrentTask.contains(normalizeTaskToken(scriptName))
        || normalizedCurrentTask.contains(normalizeTaskToken(taskKey));
  }

  private String normalizeTaskToken(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String normalized = value;
    if (normalized.regionMatches(true, 0, SCRIPT_SOURCE_PREFIX_MICRO6, 0, SCRIPT_SOURCE_PREFIX_MICRO6.length())) {
      normalized = normalized.substring(SCRIPT_SOURCE_PREFIX_MICRO6.length());
    }
    return normalized.replaceAll("[^a-zA-Z0-9]+", "").toLowerCase();
  }

  private String parseMicro6ExecuteError(HttpResponse<String> executeResponse) {
    if (executeResponse == null) {
      return "unknown executor error";
    }
    String body = executeResponse.body();
    if (body == null || body.isBlank()) {
      return "empty response body";
    }
    try {
      JsonNode errorJson = objectMapper.readTree(body);
      String errorCode = errorJson.path("error_code").asText("");
      String errorMessage = errorJson.path("error_message").asText("");
      String configurationSource = errorJson.path("configuration_source").asText("");
      StringBuilder summary = new StringBuilder();
      if (!errorCode.isBlank()) {
        summary.append(errorCode);
      }
      if (!errorMessage.isBlank()) {
        if (summary.length() > 0) {
          summary.append(": ");
        }
        summary.append(errorMessage);
      }
      if (!configurationSource.isBlank()) {
        if (summary.length() > 0) {
          summary.append(" ");
        }
        summary.append("(source=").append(configurationSource).append(")");
      }
      return summary.length() > 0 ? summary.toString() : body;
    } catch (Exception ignored) {
      return body;
    }
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
        stopMicro6Run(micro6ExecutorBaseUrl, entryId, "Cancelled from patch loop");
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
        controlMicro6Run(micro6ExecutorBaseUrl, entryId, "pause");
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
        controlMicro6Run(micro6ExecutorBaseUrl, entryId, "resume");
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
      controlMicro6Run(resolveRunMicro6BaseUrl(run), run.micro6EntryId, "pause");
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
      controlMicro6Run(resolveRunMicro6BaseUrl(run), run.micro6EntryId, "resume");
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
      stopMicro6Run(resolveRunMicro6BaseUrl(run), run.micro6EntryId, reason);
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

  @SuppressWarnings("unchecked")
  private String resolveMicro6BaseUrl(Map<String, Object> taskMetadata) {
    if (taskMetadata != null) {
      Object target = taskMetadata.get("micro6_target");
      if (target instanceof Map<?, ?> targetMap) {
        Object value = ((Map<String, Object>) targetMap).get("advertised_base_url");
        if (value != null && !value.toString().isBlank()) {
          return normalizeBaseUrl(value.toString());
        }
      }
    }
    return normalizeBaseUrl(micro6ExecutorBaseUrl);
  }

  private String resolveRunMicro6BaseUrl(ActiveRun run) {
    if (run != null && run.micro6BaseUrl != null && !run.micro6BaseUrl.isBlank()) {
      return normalizeBaseUrl(run.micro6BaseUrl);
    }
    return normalizeBaseUrl(micro6ExecutorBaseUrl);
  }

  private HttpResponse<String> queryMicro6Status(String micro6BaseUrl, int entryId)
      throws Exception {
    HttpRequest statusRequest =
        HttpRequest.newBuilder()
            .uri(URI.create(micro6BaseUrl + "/api/v1/runs/" + entryId + "/status"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
    HttpResponse<String> response =
        httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() == 404) {
      HttpRequest fallback =
          HttpRequest.newBuilder()
              .uri(URI.create(micro6BaseUrl + "/api/v1/status/" + entryId))
              .timeout(Duration.ofSeconds(10))
              .GET()
              .build();
      return httpClient.send(fallback, HttpResponse.BodyHandlers.ofString());
    }
    return response;
  }

  private void stopMicro6Run(String micro6BaseUrl, int entryId, String reason) {
    controlMicro6Run(micro6BaseUrl, entryId, "stop", reason);
  }

  private void controlMicro6Run(String micro6BaseUrl, int entryId, String action) {
    controlMicro6Run(micro6BaseUrl, entryId, action, null);
  }

  private void controlMicro6Run(
      String micro6BaseUrl, int entryId, String action, String reason) {
    try {
      String body =
          reason == null || reason.isBlank()
              ? "{}"
              : objectMapper.writeValueAsString(Map.of("reason", reason));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(micro6BaseUrl + "/api/v1/runs/" + entryId + "/" + action))
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

  /**
   * Sends a Micro6 run control request and reports whether it was accepted by the executor.
   *
   * <p>Unlike {@link #controlMicro6Run(String, int, String, String)}, this variant returns
   * {@code true} only when the executor responds with an HTTP status below 400, allowing callers
   * to distinguish a delivered control request from a fire-and-forget attempt.
   *
   * @return {@code true} if the executor accepted the request (HTTP &lt; 400); {@code false} on a
   *     non-2xx/3xx response or any network/serialization failure
   */
  private boolean controlMicro6RunRequested(
      String micro6BaseUrl, int entryId, String action, String reason) {
    try {
      String body =
          reason == null || reason.isBlank()
              ? "{}"
              : objectMapper.writeValueAsString(Map.of("reason", reason));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(micro6BaseUrl + "/api/v1/runs/" + entryId + "/" + action))
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
        return false;
      }
      return true;
    } catch (Exception e) {
      logger.warn(
          "Micro6 run control '{}' failed for entry {}: {}", action, entryId, e.getMessage());
      return false;
    }
  }

  /**
   * Reserves a Micro6 publish entry id BEFORE the /execute request is sent, so the runner can
   * persist it and reconcile against the executor even when the execute response is ambiguous.
   */
  public int reserveMicro6EntryId() {
    return micro6EntryIdSeq.incrementAndGet();
  }

  public Micro6PublishResult publishMicro6Task(String scriptName, Map<String, Object> taskMetadata) {
    return publishMicro6Task(scriptName, taskMetadata, null);
  }

  public Micro6PublishResult publishMicro6Task(
      String scriptName, Map<String, Object> taskMetadata, Integer reservedEntryId) {
    String normalizedScript = normalizeScriptName(scriptName);
    String selectedBackend = resolveBackend(scriptName);
    if (!BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(selectedBackend)) {
      return Micro6PublishResult.failure(
          "Publish is only supported for the Micro6 executor backend", micro6ExecutorBaseUrl);
    }
    final int entryId =
        reservedEntryId != null && reservedEntryId > 0
            ? reservedEntryId
            : micro6EntryIdSeq.incrementAndGet();
    final String targetBaseUrl = resolveMicro6BaseUrl(taskMetadata);
    try {
      return publishMicro6TaskInternal(normalizedScript, taskMetadata, entryId, targetBaseUrl, 0);
    } catch (Exception e) {
      logger.warn("Failed to publish Micro6 task {}: {}", normalizedScript, e.getMessage());
      return Micro6PublishResult.failure("Micro6 executor error: " + e.getMessage(), targetBaseUrl);
    }
  }

  public Micro6RunStatusResult getMicro6RunStatus(
      Map<String, Object> taskMetadata, Integer entryId) {
    String targetBaseUrl = resolveMicro6BaseUrl(taskMetadata);
    if (entryId == null || entryId <= 0) {
      return new Micro6RunStatusResult(
          false, "UNKNOWN", "Missing Micro6 entry_id", null, targetBaseUrl, entryId);
    }

    try {
      HttpResponse<String> response = queryMicro6Status(targetBaseUrl, entryId);
      if (response.statusCode() == 404) {
        return new Micro6RunStatusResult(
            false, "UNKNOWN", "entry_id not tracked", null, targetBaseUrl, entryId);
      }
      if (response.statusCode() >= 400) {
        return new Micro6RunStatusResult(
            false,
            "UNKNOWN",
            "Micro6 status polling failed (HTTP " + response.statusCode() + ")",
            null,
            targetBaseUrl,
            entryId);
      }
      JsonNode payload = objectMapper.readTree(response.body());
      return new Micro6RunStatusResult(
          true,
          payload.path("status").asText("UNKNOWN"),
          payload.path("error_message").asText(null),
          payload,
          targetBaseUrl,
          entryId);
    } catch (Exception e) {
      logger.warn("Failed to poll Micro6 status for entry {}: {}", entryId, e.getMessage());
      return new Micro6RunStatusResult(
          false,
          "UNKNOWN",
          "Micro6 status polling failed: " + e.getMessage(),
          null,
          targetBaseUrl,
          entryId);
    }
  }

  public Micro6ExecutorHealthResult getMicro6ExecutorHealth(Map<String, Object> taskMetadata) {
    String targetBaseUrl = resolveMicro6BaseUrl(taskMetadata);
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(targetBaseUrl + "/health"))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        return new Micro6ExecutorHealthResult(
            false,
            false,
            "WAITING_FOR_HEALTH",
            "Micro6 local executor returned HTTP " + response.statusCode() + " from /health",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            targetBaseUrl,
            null);
      }
      JsonNode payload = objectMapper.readTree(response.body());
      Map<String, Object> targetMetadata = readMicro6TargetMetadata(taskMetadata);
      String resolvedAccountHash =
          firstNonBlank(
              textNode(payload, "account_hash"), stringValue(targetMetadata.get("account_hash")));
      String resolvedAccountName =
          firstNonBlank(
              textNode(payload, "account_name"), stringValue(targetMetadata.get("account_name")));
      String requestedAccountName =
          firstNonBlank(
              textNode(payload, "requested_account_name"),
              stringValue(targetMetadata.get("requested_account_name")),
              stringValue(targetMetadata.get("account_name")));
      String requestedAccountHash =
          firstNonBlank(
              textNode(payload, "requested_account_hash"),
              stringValue(targetMetadata.get("requested_account_hash")),
              stringValue(targetMetadata.get("account_hash")));
      boolean dispatchHold = payload.path("dispatch_hold").asBoolean(false);
      String dispatchHoldReason = textNode(payload, "dispatch_hold_reason");
      String startupState =
          firstNonBlank(
              textNode(payload, "startup_state"), inferStartupStateFromTarget(targetMetadata, payload));
      String loginState =
          firstNonBlank(textNode(payload, "login_state"), inferLoginStateFromTarget(targetMetadata, payload));
      Integer loginAttempt = payload.path("login_attempt").isNumber() ? payload.path("login_attempt").asInt() : null;
      Integer loginAttemptMax =
          payload.path("login_attempt_max").isNumber() ? payload.path("login_attempt_max").asInt() : null;
      String loginTerminalReason = textNode(payload, "login_terminal_reason");
      boolean publishReady =
          isMicro6ExecutorPublishReady(dispatchHold, startupState, loginState, loginTerminalReason);
      String readinessPhase = publishReady ? "READY_TO_PUBLISH" : "WAITING_FOR_DISPATCH";
      String readinessReason =
          publishReady
              ? null
              : buildMicro6DispatchHoldReason(
                  dispatchHoldReason,
                  startupState,
                  loginState,
                  loginAttempt,
                  loginAttemptMax,
                  loginTerminalReason);
      return new Micro6ExecutorHealthResult(
          true,
          publishReady,
          readinessPhase,
          readinessReason,
          resolvedAccountName,
          resolvedAccountHash,
          requestedAccountName,
          requestedAccountHash,
          dispatchHold,
          dispatchHoldReason,
          startupState,
          loginState,
          loginAttempt,
          loginAttemptMax,
          loginTerminalReason,
          targetBaseUrl,
          payload);
    } catch (Exception e) {
      return new Micro6ExecutorHealthResult(
          false,
          false,
          "WAITING_FOR_HEALTH",
          "Micro6 local executor /health on " + targetBaseUrl + " is not reachable yet",
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          targetBaseUrl,
          null);
    }
  }

  /**
   * Stop request for a Builder Goal published Micro6 run, reporting delivery success.
   *
   * <p>Resolves the target base URL from the stored task metadata and posts a stop control request
   * to the Micro6 executor for the given entry id. Used by the Builder Goal Runner to request
   * cancellation of a run that has already been published. The published-run status sync loop
   * reconciles the final cancellation state afterwards.
   *
   * @param taskMetadata the stored executor task metadata (used to resolve the target base URL)
   * @param entryId the Micro6 publish entry id to stop
   * @param reason the human-readable stop reason forwarded to the executor
   * @return {@code true} if the executor accepted the stop request (HTTP &lt; 400); {@code false}
   *     on a non-2xx/3xx response or any network/serialization failure
   */
  public boolean stopMicro6PublishedRun(Map<String, Object> taskMetadata, Integer entryId, String reason) {
    if (entryId == null || entryId <= 0) {
      return false;
    }
    String targetBaseUrl = resolveMicro6BaseUrl(taskMetadata);
    return controlMicro6RunRequested(targetBaseUrl, entryId, "stop", reason);
  }

  private String normalizeBaseUrl(String value) {
    if (value == null || value.isBlank()) {
      return micro6ExecutorBaseUrl;
    }
    String normalized = value.trim();
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private String textNode(JsonNode payload, String fieldName) {
    if (payload == null || fieldName == null || fieldName.isBlank()) {
      return null;
    }
    JsonNode node = payload.path(fieldName);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text.trim();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readMicro6TargetMetadata(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return Map.of();
    }
    Object target = taskMetadata.get("micro6_target");
    if (target instanceof Map<?, ?> targetMap) {
      return new LinkedHashMap<>((Map<String, Object>) targetMap);
    }
    return Map.of();
  }

  private String inferStartupStateFromTarget(Map<String, Object> targetMetadata, JsonNode payload) {
    if (targetMetadata == null || targetMetadata.isEmpty()) {
      return null;
    }
    if (!payload.path("ok").asBoolean(false)) {
      return null;
    }
    String status = stringValue(targetMetadata.get("status"));
    if (status == null || "STALE".equalsIgnoreCase(status)) {
      return null;
    }
    if (stringValue(targetMetadata.get("advertised_base_url")) == null) {
      return null;
    }
    return "accountbuilder_ready";
  }

  private String inferLoginStateFromTarget(Map<String, Object> targetMetadata, JsonNode payload) {
    return inferStartupStateFromTarget(targetMetadata, payload) == null ? null : "logged_in";
  }

  private boolean isMicro6ExecutorPublishReady(
      boolean dispatchHold, String startupState, String loginState, String loginTerminalReason) {
    if (dispatchHold) {
      return false;
    }
    if (!"accountbuilder_ready".equalsIgnoreCase(startupState)) {
      return false;
    }
    if (loginState == null || loginState.isBlank()) {
      return true;
    }
    if ("logged_in".equalsIgnoreCase(loginState)) {
      return true;
    }
    return "auto_login_in_progress".equalsIgnoreCase(loginState) && loginTerminalReason == null
        ? false
        : false;
  }

  private String buildMicro6DispatchHoldReason(
      String dispatchHoldReason,
      String startupState,
      String loginState,
      Integer loginAttempt,
      Integer loginAttemptMax,
      String loginTerminalReason) {
    if ("initializing_bot_logic".equalsIgnoreCase(startupState)) {
      return "Micro6 local executor is logged in but AccountBuilder bot logic is still initializing";
    }
    if ("bot_initialization_failed".equalsIgnoreCase(startupState)) {
      return "Micro6 local executor failed to initialize AccountBuilder bot logic";
    }
    if ("logged_in".equalsIgnoreCase(loginState)
        && startupState != null
        && !"accountbuilder_ready".equalsIgnoreCase(startupState)) {
      return "Micro6 local executor is logged in but AccountBuilder is not ready yet: "
          + startupState;
    }
    if ("auto_login_failed".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor is waiting for manual login after auto-login exhausted "
          + firstNonBlank(formatAttemptCounts(loginAttempt, loginAttemptMax), "its attempts");
    }
    if ("authenticator_required".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor is waiting for manual authenticator input";
    }
    if ("auto_login_in_progress".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor is attempting auto-login"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("profile_bootstrap_pending".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor is preparing the requested profile for auto-login";
    }
    if ("waiting_for_profile_lock".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor is waiting to acquire the profile lock before auto-login"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("waiting_for_profile_record".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor could not find the requested profile yet"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("profile_bootstrap_failed".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor failed before auto-login could be armed";
    }
    if ("waiting_for_login_screen".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor is waiting for the login screen"
          + formatAttemptSuffix(loginAttempt, loginAttemptMax);
    }
    if ("runtime_not_ready".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor is still initializing the auto-login runtime";
    }
    if ("runtime_not_ready_timeout".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor auto-login runtime never became ready";
    }
    if ("login_screen_not_ready_timeout".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor login screen never became ready";
    }
    if ("waiting_for_login".equalsIgnoreCase(dispatchHoldReason)
        || "waiting_for_login".equalsIgnoreCase(loginState)) {
      return "Micro6 local executor is waiting for login";
    }
    if ("starting_accountbuilder".equalsIgnoreCase(dispatchHoldReason)
        && startupState != null
        && !"starting_accountbuilder".equalsIgnoreCase(startupState)) {
      return "Micro6 local executor is online but not dispatch-ready: " + startupState;
    }
    if (loginTerminalReason != null && !loginTerminalReason.isBlank()) {
      return "Micro6 local executor is online but not dispatch-ready: " + loginTerminalReason;
    }
    return "Micro6 local executor is online but not dispatch-ready: "
        + firstNonBlank(dispatchHoldReason, startupState, loginState, "dispatch_hold");
  }

  private String formatAttemptCounts(Integer loginAttempt, Integer loginAttemptMax) {
    if (loginAttempt == null || loginAttemptMax == null || loginAttemptMax <= 0) {
      return null;
    }
    return loginAttempt + "/" + loginAttemptMax;
  }

  private String formatAttemptSuffix(Integer loginAttempt, Integer loginAttemptMax) {
    String counts = formatAttemptCounts(loginAttempt, loginAttemptMax);
    return counts != null ? " (" + counts + ")" : "";
  }

  private String firstNonBlank(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return null;
  }

  private String stringValue(Object value) {
    if (value == null) {
      return null;
    }
    String text = value.toString();
    return text == null || text.isBlank() ? null : text.trim();
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
