package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.debug.buildergoal.service.AccountIdentityContract;
import com.chromascape.web.debug.buildergoal.service.CanonicalSettingsHasher;
import com.chromascape.web.debug.buildergoal.service.Micro6ExecutorReadinessEvaluator;
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

  /**
   * Resolves a display name to a canonical Micro6 task key through the task catalog. Display-name
   * identity comparison is only permitted after this resolution; a null-returning resolver (the
   * default, when no catalog is available) means display-name aliases never match.
   */
  private final java.util.function.Function<String, String> displayNameToTaskKeyResolver;

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
    this(
        stateHandler,
        taskDetectionService,
        objectMapper,
        executionBackend,
        micro6ExecutorBaseUrl,
        httpClient,
        micro6TimeoutRecoveryProbeMs,
        null);
  }

  ScriptExecutionService(
      WebSocketStateHandler stateHandler,
      TaskDetectionService taskDetectionService,
      ObjectMapper objectMapper,
      String executionBackend,
      String micro6ExecutorBaseUrl,
      HttpClient httpClient,
      long micro6TimeoutRecoveryProbeMs,
      java.util.function.Function<String, String> displayNameToTaskKeyResolver) {
    this.instanceManager = ScriptInstanceManager.getInstance();
    this.stateHandler = stateHandler;
    this.taskDetectionService = taskDetectionService;
    this.objectMapper = objectMapper;
    this.executionBackend = executionBackend;
    this.micro6ExecutorBaseUrl = micro6ExecutorBaseUrl;
    this.httpClient = httpClient;
    this.micro6TimeoutRecoveryProbeMs = Math.max(0L, micro6TimeoutRecoveryProbeMs);
    this.displayNameToTaskKeyResolver =
        displayNameToTaskKeyResolver != null ? displayNameToTaskKeyResolver : candidate -> null;
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
    public static ScriptExecutionResult reconciling(
        String backend, Integer entryId, String errorMessage) {
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
      String executorStatus,
      String activationState,
      Integer entryId,
      boolean attachedToExisting,
      String micro6BaseUrl,
      String errorMessage,
      Micro6SettingsReceipt settingsReceipt) {

    public Micro6PublishResult(
        boolean published,
        String backend,
        String publishStatus,
        String executorStatus,
        String activationState,
        Integer entryId,
        boolean attachedToExisting,
        String micro6BaseUrl,
        String errorMessage) {
      this(
          published,
          backend,
          publishStatus,
          executorStatus,
          activationState,
          entryId,
          attachedToExisting,
          micro6BaseUrl,
          errorMessage,
          null);
    }

    public static Micro6PublishResult success(
        Integer entryId,
        boolean attachedToExisting,
        String micro6BaseUrl,
        String publishStatus,
        String executorStatus,
        String activationState) {
      return success(
          entryId,
          attachedToExisting,
          micro6BaseUrl,
          publishStatus,
          executorStatus,
          activationState,
          null);
    }

    public static Micro6PublishResult success(
        Integer entryId,
        boolean attachedToExisting,
        String micro6BaseUrl,
        String publishStatus,
        String executorStatus,
        String activationState,
        Micro6SettingsReceipt settingsReceipt) {
      return new Micro6PublishResult(
          true,
          BACKEND_MICRO6_LOCAL_EXECUTOR,
          publishStatus,
          executorStatus,
          activationState,
          entryId,
          attachedToExisting,
          micro6BaseUrl,
          null,
          settingsReceipt);
    }

    /**
     * Ambiguous outcome: the execute request timed out and the executor has not (yet) confirmed
     * whether it accepted the entry. The caller must reconcile via status polling rather than
     * treating this as a terminal publish failure.
     */
    public static Micro6PublishResult reconciling(
        Integer entryId, String micro6BaseUrl, String errorMessage) {
      return new Micro6PublishResult(
          false,
          BACKEND_MICRO6_LOCAL_EXECUTOR,
          "RECONCILING",
          null,
          null,
          entryId,
          false,
          micro6BaseUrl,
          errorMessage,
          null);
    }

    public static Micro6PublishResult failure(String errorMessage, String micro6BaseUrl) {
      return new Micro6PublishResult(
          false,
          BACKEND_MICRO6_LOCAL_EXECUTOR,
          "PUBLISH_FAILED",
          null,
          null,
          null,
          false,
          micro6BaseUrl,
          errorMessage,
          null);
    }

    /**
     * A confirmed task/entry identity mismatch on a duplicate attempt: the executor holds the entry
     * for a different canonical task, or the referenced entry's identity/status cannot be safely
     * attached to. Never attach in this state.
     */
    public static Micro6PublishResult conflictingDispatch(
        Integer entryId, String micro6BaseUrl, String errorMessage) {
      return new Micro6PublishResult(
          false,
          BACKEND_MICRO6_LOCAL_EXECUTOR,
          "DUPLICATE_OR_CONFLICTING_DISPATCH",
          null,
          null,
          entryId,
          false,
          micro6BaseUrl,
          errorMessage,
          null);
    }
  }

  /** Structured identity parsed from a Micro6 409 conflict response. */
  public record Micro6ConflictIdentity(
      int existingEntryId, String taskKey, String existingStatus, String existingIterationId) {
    public Micro6ConflictIdentity(int existingEntryId, String taskKey, String existingStatus) {
      this(existingEntryId, taskKey, existingStatus, null);
    }
  }

  public record Micro6SettingsReceipt(
      String version,
      Integer entryId,
      String iterationId,
      String taskKey,
      JsonNode settings,
      String settingsSha256,
      String settingsSchemaVersion,
      String settingsSchemaHash,
      String acceptedAt,
      String configurationState) {}

  public record Micro6RunStatusResult(
      boolean found,
      String status,
      String errorMessage,
      JsonNode rawResponse,
      String micro6BaseUrl,
      Integer entryId,
      Micro6SettingsReceipt settingsReceipt) {
    public Micro6RunStatusResult(
        boolean found,
        String status,
        String errorMessage,
        JsonNode rawResponse,
        String micro6BaseUrl,
        Integer entryId) {
      this(found, status, errorMessage, rawResponse, micro6BaseUrl, entryId, null);
    }
  }

  public record Micro6ControlResult(
      boolean delivered, Micro6RunStatusResult authoritativeStatus, String errorMessage) {}

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
      JsonNode rawResponse,
      Integer requestedWorld,
      Integer currentWorld,
      Boolean worldMatched,
      Boolean pathfinderReady,
      String scriptLifecycleState,
      Boolean scriptLoopAlive) {}

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
      String executionId, String scriptName, long timeoutMs, Map<String, Object> taskMetadata) {
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
      String executionId, String scriptName, long timeoutMs, Map<String, Object> taskMetadata) {
    final long startTime = System.currentTimeMillis();
    final int entryId = micro6EntryIdSeq.incrementAndGet();
    int trackedEntryId = entryId;
    final String targetBaseUrl = resolveMicro6BaseUrl(taskMetadata);
    micro6EntryToScript.put(entryId, scriptName);
    activeMicro6EntryId.set(entryId);

    if (executionId != null) {
      activeRuns.put(
          executionId, new ActiveRun(BACKEND_MICRO6_LOCAL_EXECUTOR, entryId, null, targetBaseUrl));
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
              BACKEND_MICRO6_LOCAL_EXECUTOR, publishResult.entryId(), publishResult.errorMessage());
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
        Micro6RunStatusResult statusResult = getMicro6RunStatus(taskMetadata, trackedEntryId);
        if (statusResult.found()) {
          String status = statusResult.status();
          if ("COMPLETED".equalsIgnoreCase(status)) {
            return ScriptExecutionResult.success(
                BACKEND_MICRO6_LOCAL_EXECUTOR, "COMPLETED", System.currentTimeMillis() - startTime);
          }
          if ("FAILED".equalsIgnoreCase(status)) {
            return ScriptExecutionResult.failure(
                BACKEND_MICRO6_LOCAL_EXECUTOR,
                "FAILED",
                firstNonBlank(statusResult.errorMessage(), "Micro6 task failed"));
          }
          if ("CANCELLED".equalsIgnoreCase(status)) {
            return ScriptExecutionResult.failure(
                BACKEND_MICRO6_LOCAL_EXECUTOR,
                "CANCELLED",
                firstNonBlank(statusResult.errorMessage(), "Micro6 task was cancelled"));
          }
        } else if (statusResult.errorMessage() != null
            && statusResult.errorMessage().startsWith("Micro6 status polling failed (HTTP ")) {
          return ScriptExecutionResult.failure(
              BACKEND_MICRO6_LOCAL_EXECUTOR, "FAILED", statusResult.errorMessage());
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
              scriptName, taskKey, entryId, targetBaseUrl, timeoutException, mergedTaskMetadata);
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
                scriptName,
                taskKey,
                entryId,
                targetBaseUrl,
                retryTimeoutException,
                mergedTaskMetadata);
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
      Micro6ConflictIdentity conflictIdentity = parseConflictIdentity(executeResponse);
      if (conflictIdentity != null) {
        return resolveConflictAttachment(
            scriptName, taskKey, targetBaseUrl, conflictIdentity, mergedTaskMetadata);
      }
      String parsedExecutorError = parseMicro6ExecuteError(executeResponse);
      return Micro6PublishResult.failure(
          "Micro6 executor rejected task (HTTP "
              + executeResponse.statusCode()
              + "): "
              + parsedExecutorError,
          targetBaseUrl);
    }

    return mapExecuteAcceptedResult(
        executeResponse, entryId, taskKey, targetBaseUrl, mergedTaskMetadata);
  }

  /**
   * Parses a 409 conflict body into a structured identity. Returns null when the body is not a
   * conflict, is not valid JSON, or omits any of the three mandatory identity fields ({@code
   * existing_entry_id}, {@code task_key}, {@code existing_status}) so a missing-field conflict
   * always fails closed into the generic failure path rather than attaching on an entry id alone.
   */
  private Micro6ConflictIdentity parseConflictIdentity(HttpResponse<String> executeResponse) {
    if (executeResponse == null || executeResponse.statusCode() != 409) {
      return null;
    }
    try {
      JsonNode body = objectMapper.readTree(executeResponse.body());
      if (body == null || !body.isObject()) {
        return null;
      }
      if (!body.has("existing_entry_id") || !body.has("task_key") || !body.has("existing_status")) {
        return null;
      }
      JsonNode existingEntryNode = body.get("existing_entry_id");
      if (existingEntryNode == null || !existingEntryNode.canConvertToInt()) {
        return null;
      }
      int existingEntryId = existingEntryNode.asInt(-1);
      if (existingEntryId <= 0) {
        return null;
      }
      String taskKey = rawTextNode(body, "task_key");
      String existingStatus = textNode(body, "existing_status");
      String existingIterationId = textNode(body, "existing_iteration_id");
      if (taskKey == null
          || taskKey.isBlank()
          || existingStatus == null
          || existingStatus.isBlank()) {
        return null;
      }
      return new Micro6ConflictIdentity(
          existingEntryId, taskKey, existingStatus, existingIterationId);
    } catch (Exception e) {
      logger.debug("Failed to parse Micro6 conflict response: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Safe duplicate attachment: the conflict's {@code task_key} must exactly equal the requested
   * canonical key, the referenced entry is then polled, and only an entry whose returned id, task
   * identity, and status all match the whitelist is attached to. An ambiguous poll result returns
   * RECONCILING; a confirmed identity mismatch returns DUPLICATE_OR_CONFLICTING_DISPATCH.
   */
  private Micro6PublishResult resolveConflictAttachment(
      String scriptName,
      String taskKey,
      String targetBaseUrl,
      Micro6ConflictIdentity conflict,
      Map<String, Object> taskMetadata) {
    // Conflict attachment is an ownership decision. Only the exact canonical key may attach;
    // aliases, display names, case changes, and whitespace are rejected before polling.
    if (!matchesCanonicalTaskIdentity(conflict.taskKey(), taskKey)) {
      logger.warn(
          "Micro6 conflict task_key mismatch: requested {} but existing entry {} holds {}",
          taskKey,
          conflict.existingEntryId(),
          conflict.taskKey());
      return Micro6PublishResult.conflictingDispatch(
          conflict.existingEntryId(),
          targetBaseUrl,
          "Micro6 duplicate entry "
              + conflict.existingEntryId()
              + " is held by a different task (existing="
              + conflict.taskKey()
              + ", requested="
              + taskKey
              + ")");
    }
    // Builder Goal publishes persist the canonical run identity under
    // builder_goal_iteration_id; older patch-loop callers use iteration_id. Resolve the same
    // fallback before validating the conflict snapshot so a valid Builder Goal duplicate is not
    // rejected merely because the producer used the newer field name.
    String requestedIterationId = stringMetadata(taskMetadata, "iteration_id");
    if (requestedIterationId == null) {
      requestedIterationId = stringMetadata(taskMetadata, "builder_goal_iteration_id");
    }
    if (requestedIterationId != null
        && (conflict.existingIterationId() == null
            || !requestedIterationId.equals(conflict.existingIterationId()))) {
      return Micro6PublishResult.conflictingDispatch(
          conflict.existingEntryId(),
          targetBaseUrl,
          "Micro6 duplicate entry iteration identity did not match the requested run");
    }

    // The conflict snapshot is mandatory identity evidence, not merely advisory text. A
    // terminal/unknown snapshot cannot be made attachable by a later status response: accepting
    // that combination would allow an entry-id race or a reused entry to be attached silently.
    if (!isAttachableExistingStatus(conflict.existingStatus())) {
      return Micro6PublishResult.conflictingDispatch(
          conflict.existingEntryId(),
          targetBaseUrl,
          "Micro6 duplicate entry "
              + conflict.existingEntryId()
              + " is not in an attachable status: "
              + conflict.existingStatus());
    }

    long deadline = System.currentTimeMillis() + micro6TimeoutRecoveryProbeMs;
    while (System.currentTimeMillis() < deadline) {
      Micro6RunStatusResult statusResult =
          getMicro6RunStatus(taskMetadata, conflict.existingEntryId());
      if (statusResult.found()) {
        return verifyEntryForAttachment(
            scriptName, taskKey, targetBaseUrl, conflict, statusResult, taskMetadata);
      }
      String mismatchReason =
          confirmedStatusIdentityMismatchReason(
              statusResult.rawResponse(),
              conflict.existingEntryId(),
              taskKey,
              requestedIterationId);
      if (mismatchReason != null) {
        return Micro6PublishResult.conflictingDispatch(
            conflict.existingEntryId(),
            targetBaseUrl,
            "Micro6 duplicate entry " + conflict.existingEntryId() + " returned " + mismatchReason);
      }
      try {
        Thread.sleep(1000L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Micro6PublishResult.reconciling(
            conflict.existingEntryId(),
            targetBaseUrl,
            "Interrupted while verifying duplicate entry "
                + conflict.existingEntryId()
                + " before attach");
      }
    }
    logger.warn(
        "Micro6 duplicate attachment could not confirm identity of entry {} within the probe window; entering RECONCILING",
        conflict.existingEntryId());
    return Micro6PublishResult.reconciling(
        conflict.existingEntryId(),
        targetBaseUrl,
        "Micro6 duplicate entry "
            + conflict.existingEntryId()
            + " could not be identity-confirmed within the probe window");
  }

  private Micro6PublishResult verifyEntryForAttachment(
      String scriptName,
      String taskKey,
      String targetBaseUrl,
      Micro6ConflictIdentity conflict,
      Micro6RunStatusResult statusResult,
      Map<String, Object> taskMetadata) {
    JsonNode raw = statusResult.rawResponse();
    if (raw == null || !raw.isObject()) {
      return Micro6PublishResult.reconciling(
          conflict.existingEntryId(),
          targetBaseUrl,
          "Micro6 duplicate entry "
              + conflict.existingEntryId()
              + " returned a malformed status response");
    }
    int returnedEntryId = raw.path("entry_id").asInt(-1);
    if (returnedEntryId != conflict.existingEntryId()) {
      logger.warn(
          "Micro6 duplicate entry identity mismatch: conflict referenced {} but status returned {}",
          conflict.existingEntryId(),
          returnedEntryId);
      return Micro6PublishResult.conflictingDispatch(
          conflict.existingEntryId(),
          targetBaseUrl,
          "Micro6 duplicate entry "
              + conflict.existingEntryId()
              + " returned a mismatched entry id "
              + returnedEntryId);
    }
    String returnedTaskKey =
        firstNonBlank(rawTextNode(raw, "task_key"), rawTextNode(raw, "queued_task"));
    if (!matchesCanonicalTaskIdentity(returnedTaskKey, taskKey)) {
      logger.warn(
          "Micro6 duplicate entry task mismatch: requested {} but entry {} holds {}",
          taskKey,
          conflict.existingEntryId(),
          returnedTaskKey);
      return Micro6PublishResult.conflictingDispatch(
          conflict.existingEntryId(),
          targetBaseUrl,
          "Micro6 duplicate entry "
              + conflict.existingEntryId()
              + " returned a mismatched task identity");
    }
    String requestedIterationId = stringMetadata(taskMetadata, "iteration_id");
    if (requestedIterationId == null) {
      requestedIterationId = stringMetadata(taskMetadata, "builder_goal_iteration_id");
    }
    String returnedIterationId = rawTextNode(raw, "iteration_id");
    if (requestedIterationId != null
        && (returnedIterationId == null || !requestedIterationId.equals(returnedIterationId))) {
      return Micro6PublishResult.conflictingDispatch(
          conflict.existingEntryId(),
          targetBaseUrl,
          "Micro6 duplicate entry returned a mismatched iteration identity");
    }
    String returnedStatus = statusResult.status();
    if (!isAttachableExistingStatus(returnedStatus)) {
      logger.warn(
          "Micro6 duplicate entry {} is not in an attachable status: {}",
          conflict.existingEntryId(),
          returnedStatus);
      return Micro6PublishResult.conflictingDispatch(
          conflict.existingEntryId(),
          targetBaseUrl,
          "Micro6 duplicate entry "
              + conflict.existingEntryId()
              + " is not in an attachable status: "
              + returnedStatus);
    }
    if (!conflict.existingStatus().equalsIgnoreCase(returnedStatus)) {
      // The conflict snapshot raced the status poll. A second identity/status-confirming poll is
      // required before attach; one transient state change is not stable evidence.
      Micro6RunStatusResult secondPoll =
          getMicro6RunStatus(taskMetadata, conflict.existingEntryId());
      String mismatchReason =
          confirmedStatusIdentityMismatchReason(
              secondPoll.rawResponse(), conflict.existingEntryId(), taskKey, requestedIterationId);
      if (mismatchReason != null) {
        return Micro6PublishResult.conflictingDispatch(
            conflict.existingEntryId(),
            targetBaseUrl,
            "Micro6 duplicate entry " + conflict.existingEntryId() + " returned " + mismatchReason);
      }
      if (!secondPoll.found()
          || !sameEntryAndTask(secondPoll.rawResponse(), conflict.existingEntryId(), taskKey)
          || (requestedIterationId != null
              && !requestedIterationId.equals(
                  rawTextNode(secondPoll.rawResponse(), "iteration_id")))
          || !isAttachableExistingStatus(secondPoll.status())
          || !returnedStatus.equalsIgnoreCase(secondPoll.status())) {
        return Micro6PublishResult.reconciling(
            conflict.existingEntryId(),
            targetBaseUrl,
            "Micro6 duplicate entry status changed during identity verification; awaiting stable reconciliation");
      }
      returnedStatus = secondPoll.status();
    }
    logger.info(
        "Attached to existing Micro6 run for task {} (existing_entry_id={}, status={})",
        scriptName,
        conflict.existingEntryId(),
        returnedStatus);
    return Micro6PublishResult.success(
        conflict.existingEntryId(),
        true,
        targetBaseUrl,
        mapPublishStatusForTransport(returnedStatus),
        returnedStatus,
        rawTextNode(raw, "activation_state"),
        statusResult.settingsReceipt());
  }

  /**
   * Statuses that may safely own a re-attached run. PAUSED is intentionally included: a paused run
   * is an active (non-terminal) owned entry and can be resumed, so attachment must not be blocked.
   */
  private boolean isAttachableExistingStatus(String status) {
    if (status == null || status.isBlank()) {
      return false;
    }
    return "RECEIVED".equalsIgnoreCase(status)
        || "PENDING".equalsIgnoreCase(status)
        || "ACCEPTED".equalsIgnoreCase(status)
        || "ASSIGNED".equalsIgnoreCase(status)
        || "QUEUED".equalsIgnoreCase(status)
        || "RUNNING".equalsIgnoreCase(status)
        || "PAUSED".equalsIgnoreCase(status);
  }

  /**
   * Parses a successful {@code /execute} response. A sub-400 status is no longer treated as proof
   * of publication: the body must be valid JSON advertising {@code success=true} with a matching
   * {@code entry_id} and {@code task_key}/{@code queued_task}. Malformed, empty, or mismatched
   * bodies fail closed as RECONCILING so the status-sync loop reconciles the true outcome; an
   * explicit {@code success:false} body is a definitive publish failure.
   */
  private Micro6PublishResult mapExecuteAcceptedResult(
      HttpResponse<String> executeResponse,
      int entryId,
      String taskKey,
      String targetBaseUrl,
      Map<String, Object> taskMetadata) {
    String body = executeResponse == null ? null : executeResponse.body();
    if (body == null || body.isBlank()) {
      return Micro6PublishResult.reconciling(
          entryId,
          targetBaseUrl,
          "Micro6 execute returned an empty response body for entry " + entryId);
    }
    JsonNode accepted;
    try {
      accepted = objectMapper.readTree(body);
    } catch (Exception e) {
      logger.warn(
          "Micro6 execute response body for entry {} is not valid JSON: {}",
          entryId,
          e.getMessage());
      return Micro6PublishResult.reconciling(
          entryId,
          targetBaseUrl,
          "Micro6 execute returned a non-JSON response for entry " + entryId);
    }
    if (accepted == null || !accepted.isObject()) {
      return Micro6PublishResult.reconciling(
          entryId,
          targetBaseUrl,
          "Micro6 execute returned a malformed response for entry " + entryId);
    }
    if (!accepted.path("success").asBoolean(false)) {
      return Micro6PublishResult.failure(
          "Micro6 executor did not accept the task (success=false for entry " + entryId + ")",
          targetBaseUrl);
    }
    int returnedEntryId = accepted.path("entry_id").asInt(-1);
    if (returnedEntryId != entryId) {
      return Micro6PublishResult.reconciling(
          entryId,
          targetBaseUrl,
          "Micro6 execute returned a mismatched entry_id (expected "
              + entryId
              + ", got "
              + returnedEntryId
              + ")");
    }
    String returnedTaskKey = rawTextNode(accepted, "task_key");
    String returnedQueuedTask = rawTextNode(accepted, "queued_task");
    boolean taskIdentityMatches =
        matchesTaskIdentity(returnedTaskKey, taskKey)
            || matchesTaskIdentity(returnedQueuedTask, taskKey);
    if (!taskIdentityMatches) {
      return Micro6PublishResult.reconciling(
          entryId,
          targetBaseUrl,
          "Micro6 execute returned a mismatched task identity for entry " + entryId);
    }
    Micro6SettingsReceipt settingsReceipt =
        parseSettingsReceipt(accepted, taskMetadata, entryId, taskKey);
    if (settingsReceipt == null && requiresSettingsReceipt(taskMetadata)) {
      String receiptError =
          hasConfirmedReceiptIdentity(accepted, entryId, taskKey)
              ? "SETTINGS_RECEIPT_MISMATCH"
              : "Micro6 execute response did not contain a valid settings receipt for entry "
                  + entryId;
      return Micro6PublishResult.reconciling(entryId, targetBaseUrl, receiptError);
    }
    String executorStatus = firstNonBlank(textNode(accepted, "status"), "ACCEPTED");
    String activationState = textNode(accepted, "activation_state");
    return Micro6PublishResult.success(
        entryId,
        false,
        targetBaseUrl,
        mapPublishStatusForTransport(executorStatus),
        executorStatus,
        activationState,
        settingsReceipt);
  }

  private boolean hasConfirmedReceiptIdentity(
      JsonNode response, int expectedEntryId, String expectedTaskKey) {
    if (response == null || !response.isObject() || expectedTaskKey == null) {
      return false;
    }
    int returnedEntryId = response.path("entry_id").asInt(-1);
    String returnedTaskKey =
        firstNonBlank(rawTextNode(response, "task_key"), rawTextNode(response, "queued_task"));
    return returnedEntryId == expectedEntryId
        && matchesCanonicalTaskIdentity(returnedTaskKey, expectedTaskKey);
  }

  private boolean requiresSettingsReceipt(Map<String, Object> taskMetadata) {
    if (taskMetadata == null) {
      return false;
    }
    Object explicit = taskMetadata.get("requires_settings_receipt");
    if (explicit instanceof Boolean && (Boolean) explicit) {
      return true;
    }
    if (explicit instanceof String && Boolean.parseBoolean((String) explicit)) {
      return true;
    }
    return Boolean.TRUE.equals(taskMetadata.get("builder_goal_dispatch"))
        || taskMetadata.containsKey("builder_goal_iteration_id");
  }

  private Micro6SettingsReceipt parseSettingsReceipt(
      JsonNode response,
      Map<String, Object> taskMetadata,
      int expectedEntryId,
      String expectedTaskKey) {
    if (response == null || !response.isObject()) {
      return null;
    }
    JsonNode receipt = response.get("settings_receipt");
    if (receipt == null || !receipt.isObject()) {
      return null;
    }
    JsonNode settings = receipt.get("settings");
    if (settings == null || !settings.isObject()) {
      return null;
    }
    Integer receiptEntryId =
        receipt.path("entry_id").isIntegralNumber() ? receipt.path("entry_id").asInt() : null;
    String receiptIterationId = textNode(receipt, "iteration_id");
    String expectedIterationId = stringMetadata(taskMetadata, "iteration_id");
    if (expectedIterationId == null) {
      expectedIterationId = stringMetadata(taskMetadata, "builder_goal_iteration_id");
    }
    String receiptTaskKey = rawTextNode(receipt, "task_key");
    JsonNode expectedSettings =
        taskMetadata != null && taskMetadata.get("settings") != null
            ? objectMapper.valueToTree(taskMetadata.get("settings"))
            : objectMapper.createObjectNode();
    String expectedHash = CanonicalSettingsHasher.sha256(objectMapper, expectedSettings);
    String receiptHash = textNode(receipt, "settings_sha256");
    String schemaVersion = textNode(receipt, "settings_schema_version");
    String schemaHash = textNode(receipt, "settings_schema_hash");
    String contractVersion = textNode(receipt, "contract");
    String expectedSchemaVersion = stringMetadata(taskMetadata, "settings_schema_version");
    String expectedSchemaHash = stringMetadata(taskMetadata, "settings_schema_hash");
    String acceptedAt = textNode(receipt, "accepted_at");
    String version = textNode(receipt, "version");
    String configurationState = textNode(receipt, "configuration_state");
    boolean acceptedAtValid = false;
    if (acceptedAt != null) {
      try {
        java.time.Instant.parse(acceptedAt);
        acceptedAtValid = true;
      } catch (RuntimeException ignored) {
        acceptedAtValid = false;
      }
    }
    String actualSettingsHash = CanonicalSettingsHasher.sha256(objectMapper, settings);
    if (receiptEntryId == null
        || receiptEntryId != expectedEntryId
        || receiptTaskKey == null
        || !receiptTaskKey.equals(expectedTaskKey)
        || receiptIterationId == null
        || expectedIterationId == null
        || !receiptIterationId.equals(expectedIterationId)
        || !CanonicalSettingsHasher.canonicalJson(settings)
            .equals(CanonicalSettingsHasher.canonicalJson(expectedSettings))
        || receiptHash == null
        || !expectedHash.equals(receiptHash)
        || !actualSettingsHash.equals(receiptHash)
        || schemaVersion == null
        || schemaHash == null
        || (expectedSchemaVersion != null && !expectedSchemaVersion.equals(schemaVersion))
        || (expectedSchemaHash != null && !expectedSchemaHash.equals(schemaHash))
        || !acceptedAtValid
        || version == null
        || !"builder-settings-receipt/v1".equals(version)
        || !"builder-settings-canonical-json/v1".equals(contractVersion)
        || !("PENDING".equals(configurationState)
            || "APPLIED".equals(configurationState)
            || "FAILED".equals(configurationState))) {
      return null;
    }
    return new Micro6SettingsReceipt(
        version,
        receiptEntryId,
        receiptIterationId,
        receiptTaskKey,
        settings.deepCopy(),
        receiptHash,
        schemaVersion,
        schemaHash,
        acceptedAt,
        configurationState);
  }

  /**
   * Matches a successful execute response. A display name is accepted only when the catalog
   * resolver explicitly maps that exact token to the requested canonical key. No implicit case,
   * punctuation, alias, or whitespace normalization is performed.
   */
  private boolean matchesTaskIdentity(String candidate, String taskKey) {
    if (candidate == null || candidate.isBlank() || taskKey == null || taskKey.isBlank()) {
      return false;
    }
    if (candidate.equals(taskKey)) {
      return true;
    }
    try {
      String resolved = displayNameToTaskKeyResolver.apply(candidate);
      return resolved != null && resolved.equals(taskKey);
    } catch (RuntimeException resolverFailure) {
      logger.debug(
          "Micro6 task identity resolver failed for candidate {}: {}",
          candidate,
          resolverFailure.getMessage());
      return false;
    }
  }

  /** Strict identity comparison used for duplicate attachment and status reconciliation. */
  private boolean matchesCanonicalTaskIdentity(String candidate, String taskKey) {
    return candidate != null
        && taskKey != null
        && !candidate.isBlank()
        && !taskKey.isBlank()
        && candidate.equals(taskKey);
  }

  private boolean sameEntryAndTask(JsonNode raw, int entryId, String taskKey) {
    if (raw == null || !raw.isObject() || raw.path("entry_id").asInt(-1) != entryId) {
      return false;
    }
    String returnedTaskKey =
        firstNonBlank(rawTextNode(raw, "task_key"), rawTextNode(raw, "queued_task"));
    return matchesCanonicalTaskIdentity(returnedTaskKey, taskKey);
  }

  /** Maps an executor status to the transport publish-status vocabulary for the immediate UI. */
  private String mapPublishStatusForTransport(String executorStatus) {
    if (executorStatus == null || executorStatus.isBlank()) {
      return "RECONCILING";
    }
    if ("QUEUED".equalsIgnoreCase(executorStatus)) {
      return "QUEUED";
    }
    if ("ACCEPTED".equalsIgnoreCase(executorStatus)
        || "RECEIVED".equalsIgnoreCase(executorStatus)
        || "PENDING".equalsIgnoreCase(executorStatus)) {
      return "ACCEPTED";
    }
    if ("ASSIGNED".equalsIgnoreCase(executorStatus)) {
      return "ASSIGNED";
    }
    if ("RUNNING".equalsIgnoreCase(executorStatus)) {
      return "RUNNING";
    }
    if ("PAUSED".equalsIgnoreCase(executorStatus)) {
      // PAUSED is an activated-but-suspended state, never collapsed into RUNNING. Keeping it
      // distinct in the transport vocabulary lets the planner label it "Task paused" and leaves
      // resume/stop controls active without implying a running script loop.
      return "PAUSED";
    }
    return "RECONCILING";
  }

  private Micro6PublishResult recoverAcceptedPublishAfterTimeout(
      String scriptName,
      String taskKey,
      int entryId,
      String targetBaseUrl,
      HttpTimeoutException timeoutException,
      Map<String, Object> taskMetadata) {
    long deadline = System.currentTimeMillis() + micro6TimeoutRecoveryProbeMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        Micro6RunStatusResult statusResult = getMicro6RunStatus(taskMetadata, entryId);
        // A status value alone is not proof that the reserved entry was accepted. The executor
        // must echo the exact entry id and canonical task key before timeout recovery can attach
        // the persisted run to that status.
        if (isAcceptedPublishStatus(statusResult)
            && sameEntryAndTask(statusResult.rawResponse(), entryId, taskKey)) {
          logger.info(
              "Recovered timed out Micro6 publish for task {} on {} via status {} for entry {}",
              scriptName,
              targetBaseUrl,
              statusResult.status(),
              entryId);
          return Micro6PublishResult.success(
              entryId,
              false,
              targetBaseUrl,
              mapPublishStatusForTransport(statusResult.status()),
              statusResult.status(),
              rawTextNode(statusResult.rawResponse(), "activation_state"),
              statusResult.settingsReceipt());
        }
      } catch (Exception statusException) {
        logger.debug(
            "Micro6 publish timeout recovery status poll failed for entry {}: {}",
            entryId,
            statusException.getMessage());
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
        || "RECEIVED".equalsIgnoreCase(status)
        || "QUEUED".equalsIgnoreCase(status)
        || "ASSIGNED".equalsIgnoreCase(status)
        || "RUNNING".equalsIgnoreCase(status)
        || "PAUSED".equalsIgnoreCase(status)
        || "COMPLETED".equalsIgnoreCase(status)
        || "ACCEPTED".equalsIgnoreCase(status);
  }

  /**
   * Health-based timeout recovery is only valid when health exposes the same entry identity and
   * canonical task key. A display name/current_task without an entry id is ambiguous and must
   * remain RECONCILING.
   */
  private boolean matchesPublishedHealthEntry(JsonNode rawHealth, int entryId, String taskKey) {
    if (rawHealth == null || !rawHealth.isObject()) {
      return false;
    }
    int reportedEntryId = rawHealth.path("current_entry_id").asInt(-1);
    if (reportedEntryId < 0) {
      reportedEntryId = rawHealth.path("entry_id").asInt(-1);
    }
    if (reportedEntryId != entryId) {
      return false;
    }
    String reportedTaskKey =
        firstNonBlank(rawTextNode(rawHealth, "task_key"), rawTextNode(rawHealth, "queued_task"));
    return matchesCanonicalTaskIdentity(reportedTaskKey, taskKey);
  }

  private String normalizeTaskToken(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    String normalized = value;
    if (normalized.regionMatches(
        true, 0, SCRIPT_SOURCE_PREFIX_MICRO6, 0, SCRIPT_SOURCE_PREFIX_MICRO6.length())) {
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
    boolean selectedTargetRequired =
        taskMetadata != null
            && (taskMetadata.containsKey("micro6_target")
                || Boolean.TRUE.equals(taskMetadata.get("publish_only"))
                || taskMetadata.containsKey("builder_goal_iteration_id"));
    if (taskMetadata != null) {
      Object target = taskMetadata.get("micro6_target");
      if (target instanceof Map<?, ?> targetMap) {
        Object value = ((Map<String, Object>) targetMap).get("local_executor_base_url");
        if (value != null && !value.toString().isBlank()) {
          return normalizeBaseUrl(value.toString());
        }
      }
      Object preparation = taskMetadata.get("target_preparation");
      if (preparation instanceof Map<?, ?> preparationMap) {
        Object value =
            ((Map<String, Object>) preparationMap).get("local_executor_base_url");
        if (value != null && !value.toString().isBlank()) {
          return normalizeBaseUrl(value.toString());
        }
      }
      if (target instanceof Map<?, ?> targetMap) {
        Object value = ((Map<String, Object>) targetMap).get("advertised_base_url");
        if (value != null && !value.toString().isBlank()) {
          return normalizeBaseUrl(value.toString());
        }
      }
    }
    // Builder Goal dispatch is registry-selected. Never substitute the configured local default
    // when a persisted run has no selected target metadata.
    return selectedTargetRequired ? null : normalizeBaseUrl(micro6ExecutorBaseUrl);
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

  private void controlMicro6Run(String micro6BaseUrl, int entryId, String action, String reason) {
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
   * <p>Unlike {@link #controlMicro6Run(String, int, String, String)}, this variant returns {@code
   * true} only when the executor responds with an HTTP status below 400, allowing callers to
   * distinguish a delivered control request from a fire-and-forget attempt.
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

  public Micro6PublishResult publishMicro6Task(
      String scriptName, Map<String, Object> taskMetadata) {
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
      String expectedTaskKey = expectedStatusTaskKey(taskMetadata);
      String expectedIterationId = expectedStatusIterationId(taskMetadata);
      // Receipt parsing retains the historical task_name/script-name fallback for generic
      // callers; the stricter status identity gate above only opts in when a canonical task_key
      // was explicitly persisted.
      String receiptTaskKey = resolveMicro6TaskKey(null, taskMetadata);
      String identityError =
          validateStatusIdentity(payload, entryId, expectedTaskKey, expectedIterationId);
      if (identityError != null) {
        return new Micro6RunStatusResult(
            false, "RECONCILING", identityError, payload, targetBaseUrl, entryId, null);
      }
      Micro6SettingsReceipt receipt =
          parseSettingsReceipt(payload, taskMetadata, entryId, receiptTaskKey);
      if (receipt == null && requiresSettingsReceipt(taskMetadata)) {
        String receiptError =
            hasConfirmedReceiptIdentity(payload, entryId, receiptTaskKey)
                ? "SETTINGS_RECEIPT_MISMATCH"
                : "Micro6 status response did not contain a valid settings receipt";
        return new Micro6RunStatusResult(
            false, "RECONCILING", receiptError, payload, targetBaseUrl, entryId, null);
      }
      return new Micro6RunStatusResult(
          true,
          payload.path("status").asText("UNKNOWN"),
          payload.path("error_message").asText(null),
          payload,
          targetBaseUrl,
          entryId,
          receipt);
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

  /**
   * Returns the canonical task identity that a status response must echo when the caller supplied
   * one. Generic legacy status callers intentionally do not opt into identity validation.
   */
  private String expectedStatusTaskKey(Map<String, Object> taskMetadata) {
    return stringMetadata(taskMetadata, "task_key");
  }

  /** Returns the exact Builder Goal/patch-loop iteration identity required for status polling. */
  private String expectedStatusIterationId(Map<String, Object> taskMetadata) {
    String iterationId = stringMetadata(taskMetadata, "iteration_id");
    return iterationId != null
        ? iterationId
        : stringMetadata(taskMetadata, "builder_goal_iteration_id");
  }

  /**
   * Validates the identity fields returned by a status endpoint. A caller that did not provide a
   * task or iteration identity remains compatible with older generic status consumers; once either
   * identity is present, entry_id and the corresponding returned identity are mandatory.
   *
   * <p>The returned value is deliberately a stable, non-sensitive error token. It is persisted in
   * the reconciliation state but never includes executor-provided task names or payload contents.
   */
  private String validateStatusIdentity(
      JsonNode payload, int expectedEntryId, String expectedTaskKey, String expectedIterationId) {
    if (expectedTaskKey == null && expectedIterationId == null) {
      return null;
    }
    if (payload == null || !payload.isObject()) {
      return "STATUS_IDENTITY_MISSING";
    }
    JsonNode entryNode = payload.get("entry_id");
    if (entryNode == null || !entryNode.canConvertToInt()) {
      return "STATUS_IDENTITY_MISSING";
    }
    if (entryNode.asInt() != expectedEntryId) {
      return "STATUS_IDENTITY_MISMATCH";
    }
    if (expectedTaskKey != null) {
      String returnedTaskKey =
          firstNonBlank(rawTextNode(payload, "task_key"), rawTextNode(payload, "queued_task"));
      if (returnedTaskKey == null) {
        return "STATUS_IDENTITY_MISSING";
      }
      if (!matchesCanonicalTaskIdentity(returnedTaskKey, expectedTaskKey)) {
        return "STATUS_IDENTITY_MISMATCH";
      }
    }
    if (expectedIterationId != null) {
      String returnedIterationId =
          firstNonBlank(
              rawTextNode(payload, "iteration_id"),
              rawTextNode(payload, "builder_goal_iteration_id"));
      if (returnedIterationId == null) {
        return "STATUS_IDENTITY_MISSING";
      }
      if (!expectedIterationId.equals(returnedIterationId)) {
        return "STATUS_IDENTITY_MISMATCH";
      }
    }
    return null;
  }

  private String confirmedStatusIdentityMismatchReason(
      JsonNode payload, int expectedEntryId, String expectedTaskKey, String expectedIterationId) {
    if (!"STATUS_IDENTITY_MISMATCH"
        .equals(
            validateStatusIdentity(
                payload, expectedEntryId, expectedTaskKey, expectedIterationId))) {
      return null;
    }
    JsonNode entryNode = payload != null ? payload.get("entry_id") : null;
    if (entryNode != null && entryNode.canConvertToInt() && entryNode.asInt() != expectedEntryId) {
      return "a mismatched entry id " + entryNode.asInt();
    }
    if (expectedTaskKey != null) {
      String returnedTaskKey =
          firstNonBlank(rawTextNode(payload, "task_key"), rawTextNode(payload, "queued_task"));
      if (!matchesCanonicalTaskIdentity(returnedTaskKey, expectedTaskKey)) {
        return "a mismatched task identity";
      }
    }
    if (expectedIterationId != null) {
      String returnedIterationId =
          firstNonBlank(
              rawTextNode(payload, "iteration_id"),
              rawTextNode(payload, "builder_goal_iteration_id"));
      if (!expectedIterationId.equals(returnedIterationId)) {
        return "a mismatched iteration identity";
      }
    }
    return "a confirmed identity mismatch";
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
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
            null,
            null,
            null,
            null,
            null,
            null,
            null);
      }
      JsonNode payload = objectMapper.readTree(response.body());
      Map<String, Object> targetMetadata = readMicro6TargetMetadata(taskMetadata);
      // account_hash is actual logged-in identity only. Never backfill it from launch/profile
      // metadata, because requested identity is not evidence of the account currently logged in.
      String resolvedAccountHash = textNode(payload, "account_hash");
      String resolvedAccountName = textNode(payload, "account_name");
      String requestedAccountName =
          firstNonBlank(
              textNode(payload, "requested_account_name"),
              stringValue(targetMetadata.get("requested_account_name")),
              stringValue(targetMetadata.get("account_name")));
      String requestedAccountHash =
          firstNonBlank(
              textNode(payload, "requested_account_hash"),
              stringValue(targetMetadata.get("requested_account_hash")));
      Boolean dispatchHold =
          payload.path("dispatch_hold").isBoolean()
              ? payload.path("dispatch_hold").asBoolean()
              : null;
      String dispatchHoldReason = textNode(payload, "dispatch_hold_reason");
      String startupState = textNode(payload, "startup_state");
      String loginState = textNode(payload, "login_state");
      Integer loginAttempt =
          payload.path("login_attempt").isNumber() ? payload.path("login_attempt").asInt() : null;
      Integer loginAttemptMax =
          payload.path("login_attempt_max").isNumber()
              ? payload.path("login_attempt_max").asInt()
              : null;
      String loginTerminalReason = textNode(payload, "login_terminal_reason");
      // World verification: never label the requested world as the observed world unless Micro6
      // confirms the client is logged into it. Missing observed world is never matched.
      Integer requestedWorld = intValue(taskMetadata.get("world"));
      Integer observedWorld =
          payload.path("current_world").isNumber() ? payload.path("current_world").asInt() : null;
      boolean worldMatched =
          requestedWorld != null
              && observedWorld != null
              && requestedWorld.intValue() == observedWorld.intValue();
      boolean pathfinderReady = payload.path("pathfinder_ready").asBoolean(false);
      String scriptLifecycleState = textNode(payload, "script_lifecycle_state");
      boolean scriptLoopAlive = payload.path("script_loop_alive").asBoolean(false);
      // Shared readiness classification in the canonical ordering port → health → account → login →
      // world → script → dispatch. A client still logging in is reported as WAITING_FOR_LOGIN,
      // never
      // as WAITING_FOR_WORLD.
      Micro6ExecutorReadinessEvaluator.Evaluation readiness =
          Micro6ExecutorReadinessEvaluator.evaluate(
              dispatchHold,
              dispatchHoldReason,
              startupState,
              loginState,
              loginAttempt,
              loginAttemptMax,
              loginTerminalReason,
              worldMatched,
              requestedWorld,
              observedWorld,
              pathfinderReady,
              scriptLoopAlive,
              scriptLifecycleState);
      String expectedAccountHash =
          firstNonBlank(
              stringValue(targetMetadata.get("account_hash")),
              stringValue(targetMetadata.get("requested_account_hash")));
      String expectedAccountName =
          firstNonBlank(
              stringValue(targetMetadata.get("account_name")),
              stringValue(targetMetadata.get("requested_account_name")));
      String accountHashScheme = textNode(payload, "account_hash_scheme");
      // A canonical actual hash and the accountconnector/v1 scheme are mandatory readiness
      // fields.  Name matching is only a fallback for selecting an expected identity; it can
      // never turn a missing/malformed actual hash into a publish-ready executor.
      if (!AccountIdentityContract.hasRequiredScheme(accountHashScheme)
          || !AccountIdentityContract.isCanonicalHash(resolvedAccountHash)) {
        readiness =
            new Micro6ExecutorReadinessEvaluator.Evaluation(
                "WAITING_FOR_ACCOUNT",
                "Micro6 local executor did not report a canonical account hash and scheme",
                false);
      }
      if (expectedAccountHash != null
          && (!AccountIdentityContract.hasRequiredScheme(accountHashScheme)
              || !AccountIdentityContract.matchesCanonicalHash(
                  resolvedAccountHash, expectedAccountHash)
              || (textNode(payload, "requested_account_hash") != null
                  && !AccountIdentityContract.matchesCanonicalHash(
                      textNode(payload, "requested_account_hash"), expectedAccountHash)))) {
        readiness =
            new Micro6ExecutorReadinessEvaluator.Evaluation(
                "WAITING_FOR_ACCOUNT",
                "Micro6 local executor actual account identity does not match the selected canonical account",
                false);
      }
      // A canonical hash is authoritative whenever the selected target provides one. Only when
      // no expected hash exists may the legacy account name be used as the identity fallback; a
      // name mismatch must not allow readiness to proceed.
      if (expectedAccountHash == null
          && resolvedAccountHash != null
          && !resolvedAccountHash.isBlank()
          && !AccountIdentityContract.isCanonicalHash(resolvedAccountHash)) {
        readiness =
            new Micro6ExecutorReadinessEvaluator.Evaluation(
                "WAITING_FOR_ACCOUNT",
                "Micro6 local executor reported a malformed canonical account hash",
                false);
      }
      if (expectedAccountHash == null
          && expectedAccountName != null
          && !AccountIdentityContract.normalizeName(expectedAccountName)
              .equals(AccountIdentityContract.normalizeName(resolvedAccountName))) {
        readiness =
            new Micro6ExecutorReadinessEvaluator.Evaluation(
                "WAITING_FOR_ACCOUNT",
                "Micro6 local executor actual account name does not match the selected account",
                false);
      }
      return new Micro6ExecutorHealthResult(
          true,
          readiness.publishReady(),
          readiness.phase(),
          readiness.reason(),
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
          payload,
          requestedWorld,
          observedWorld,
          worldMatched,
          pathfinderReady,
          scriptLifecycleState,
          scriptLoopAlive);
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
          null,
          null,
          null,
          null,
          null,
          null,
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
  public boolean stopMicro6PublishedRun(
      Map<String, Object> taskMetadata, Integer entryId, String reason) {
    return requestStopMicro6PublishedRun(taskMetadata, entryId, reason).delivered();
  }

  public Micro6ControlResult requestStopMicro6PublishedRun(
      Map<String, Object> taskMetadata, Integer entryId, String reason) {
    return controlPublishedRunResult(taskMetadata, entryId, "stop", reason);
  }

  public boolean pauseMicro6PublishedRun(Map<String, Object> taskMetadata, Integer entryId) {
    return requestPauseMicro6PublishedRun(taskMetadata, entryId).delivered();
  }

  public boolean resumeMicro6PublishedRun(Map<String, Object> taskMetadata, Integer entryId) {
    return requestResumeMicro6PublishedRun(taskMetadata, entryId).delivered();
  }

  public Micro6ControlResult requestPauseMicro6PublishedRun(
      Map<String, Object> taskMetadata, Integer entryId) {
    return controlPublishedRunResult(taskMetadata, entryId, "pause", null);
  }

  public Micro6ControlResult requestResumeMicro6PublishedRun(
      Map<String, Object> taskMetadata, Integer entryId) {
    return controlPublishedRunResult(taskMetadata, entryId, "resume", null);
  }

  private boolean controlPublishedRun(
      Map<String, Object> taskMetadata, Integer entryId, String action, String reason) {
    return controlPublishedRunResult(taskMetadata, entryId, action, reason).delivered();
  }

  private Micro6ControlResult controlPublishedRunResult(
      Map<String, Object> taskMetadata, Integer entryId, String action, String reason) {
    if (entryId == null || entryId <= 0) {
      return new Micro6ControlResult(false, null, "Missing Micro6 entry_id");
    }
    String targetBaseUrl = resolveMicro6BaseUrl(taskMetadata);
    if (targetBaseUrl == null || targetBaseUrl.isBlank()) {
      return new Micro6ControlResult(false, null, "Missing selected Micro6 target");
    }
    try {
      String body =
          reason == null || reason.isBlank()
              ? "{}"
              : objectMapper.writeValueAsString(Map.of("reason", reason));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(targetBaseUrl + "/api/v1/runs/" + entryId + "/" + action))
              .header("Content-Type", "application/json")
              .timeout(Duration.ofSeconds(10))
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        return new Micro6ControlResult(
            false,
            null,
            "Micro6 " + action + " request failed (HTTP " + response.statusCode() + ")");
      }
      if (response.body() == null || response.body().isBlank()) {
        return new Micro6ControlResult(true, null, null);
      }
      JsonNode payload = objectMapper.readTree(response.body());
      String taskKey = resolveMicro6TaskKey(null, taskMetadata);
      Micro6SettingsReceipt receipt = parseSettingsReceipt(payload, taskMetadata, entryId, taskKey);
      if (receipt == null && requiresSettingsReceipt(taskMetadata)) {
        return new Micro6ControlResult(
            true,
            new Micro6RunStatusResult(
                false,
                "RECONCILING",
                "Micro6 control response did not contain a valid settings receipt",
                payload,
                targetBaseUrl,
                entryId,
                null),
            null);
      }
      return new Micro6ControlResult(
          true,
          new Micro6RunStatusResult(
              true,
              payload.path("status").asText("UNKNOWN"),
              payload.path("error_message").asText(null),
              payload,
              targetBaseUrl,
              entryId,
              receipt),
          null);
    } catch (Exception e) {
      return new Micro6ControlResult(false, null, "Micro6 " + action + " request failed");
    }
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

  /** Returns a JSON text value without trimming identity-bearing whitespace. */
  private String rawTextNode(JsonNode payload, String fieldName) {
    if (payload == null || fieldName == null || fieldName.isBlank()) {
      return null;
    }
    JsonNode node = payload.path(fieldName);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    String text = node.asText(null);
    return text == null || text.isBlank() ? null : text;
  }

  private Integer intValue(Object value) {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String stringValue) {
      try {
        return Integer.parseInt(stringValue.trim());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
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
