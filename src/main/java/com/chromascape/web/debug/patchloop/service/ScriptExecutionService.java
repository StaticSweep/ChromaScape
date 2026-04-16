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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
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

  private final ScriptInstanceManager instanceManager;
  private final WebSocketStateHandler stateHandler;
  private final TaskDetectionService taskDetectionService;
  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final String executionBackend;
  private final String micro6ExecutorBaseUrl;
  private final AtomicInteger micro6EntryIdSeq = new AtomicInteger(1000);
  private final Map<Integer, String> micro6EntryToScript = new ConcurrentHashMap<>();

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

    public static ScriptExecutionResult success(String backend, String terminalStatus, long durationMs) {
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
   * Runs a script and waits for completion.
   *
   * @param scriptName the name of the script to run
   * @param timeoutMs the maximum time to wait in milliseconds
   * @return the execution result
   */
  public ScriptExecutionResult runScriptAndWait(String scriptName, long timeoutMs) {
    if (BACKEND_MICRO6_LOCAL_EXECUTOR.equalsIgnoreCase(executionBackend)) {
      return runViaMicro6LocalExecutor(scriptName, timeoutMs);
    }
    if (!BACKEND_LOCAL_INSTANCE.equalsIgnoreCase(executionBackend)) {
      logger.warn(
          "Unknown execution backend '{}', falling back to {}",
          executionBackend,
          BACKEND_LOCAL_INSTANCE);
    }
    return runViaLocalInstance(scriptName, timeoutMs);
  }

  private ScriptExecutionResult runViaLocalInstance(String scriptName, long timeoutMs) {
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
        return ScriptExecutionResult.failure(
            BACKEND_LOCAL_INSTANCE, "FAILED", errorMessage.get());
      }

    } catch (Exception e) {
      logger.error("Failed to start script: {}", scriptName, e);
      return ScriptExecutionResult.failure(BACKEND_LOCAL_INSTANCE, "FAILED", e.getMessage());
    }
  }

  private ScriptExecutionResult runViaMicro6LocalExecutor(String scriptName, long timeoutMs) {
    final long startTime = System.currentTimeMillis();
    final int entryId = micro6EntryIdSeq.incrementAndGet();
    micro6EntryToScript.put(entryId, scriptName);

    try {
      String requestBody =
          objectMapper.writeValueAsString(
              Map.of(
                  "task_key", scriptName,
                  "entry_id", entryId,
                  "timeout_ms", timeoutMs,
                  "task_metadata", Map.of("display_name", scriptName, "task_name", scriptName)));

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
        return ScriptExecutionResult.failure(
            BACKEND_MICRO6_LOCAL_EXECUTOR,
            "FAILED",
            "Micro6 executor rejected task (HTTP "
                + executeResponse.statusCode()
                + "): "
                + executeResponse.body());
      }

      long deadline = System.currentTimeMillis() + timeoutMs;
      while (System.currentTimeMillis() <= deadline) {
        HttpRequest statusRequest =
            HttpRequest.newBuilder()
                .uri(URI.create(micro6ExecutorBaseUrl + "/api/v1/status/" + entryId))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> statusResponse =
            httpClient.send(statusRequest, HttpResponse.BodyHandlers.ofString());

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
        } else if (statusResponse.statusCode() >= 400 && statusResponse.statusCode() != 404) {
          return ScriptExecutionResult.failure(
              BACKEND_MICRO6_LOCAL_EXECUTOR,
              "FAILED",
              "Micro6 status polling failed (HTTP " + statusResponse.statusCode() + ")");
        }

        Thread.sleep(1000);
      }

      return ScriptExecutionResult.timeout(BACKEND_MICRO6_LOCAL_EXECUTOR);

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return ScriptExecutionResult.failure(
          BACKEND_MICRO6_LOCAL_EXECUTOR,
          "FAILED",
          "Interrupted while waiting for Micro6 executor");
    } catch (Exception e) {
      logger.error("Failed Micro6 executor run for script: {}", scriptName, e);
      return ScriptExecutionResult.failure(
          BACKEND_MICRO6_LOCAL_EXECUTOR, "FAILED", "Micro6 executor error: " + e.getMessage());
    } finally {
      micro6EntryToScript.remove(entryId);
    }
  }

  /** Gets the current script instance if running. */
  public ScriptInstance getCurrentInstance() {
    return instanceManager.getInstanceRef();
  }

  /** Stops the currently running script. */
  public void stopCurrentScript() {
    ScriptInstance instance = instanceManager.getInstanceRef();
    if (instance != null) {
      instance.stop();
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
}
