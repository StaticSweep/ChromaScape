package com.chromascape.web.debug.patchloop.service;

import com.chromascape.web.instance.RunConfig;
import com.chromascape.web.instance.ScriptInstance;
import com.chromascape.web.instance.ScriptInstanceManager;
import com.chromascape.web.instance.WebSocketStateHandler;
import com.chromascape.web.recording.service.TaskDetectionService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Service for executing scripts and tracking their completion.
 *
 * <p>Wraps the ScriptInstanceManager to provide script execution with polling and result tracking.
 */
@Service
public class ScriptExecutionService {

  private static final Logger logger = LoggerFactory.getLogger(ScriptExecutionService.class);

  private final ScriptInstanceManager instanceManager;
  private final WebSocketStateHandler stateHandler;
  private final TaskDetectionService taskDetectionService;

  @Autowired
  public ScriptExecutionService(
      WebSocketStateHandler stateHandler, TaskDetectionService taskDetectionService) {
    this.instanceManager = ScriptInstanceManager.getInstance();
    this.stateHandler = stateHandler;
    this.taskDetectionService = taskDetectionService;
  }

  /** Result of a script execution. */
  public record ScriptExecutionResult(boolean success, String errorMessage, long durationMs) {

    public static ScriptExecutionResult success(long durationMs) {
      return new ScriptExecutionResult(true, null, durationMs);
    }

    public static ScriptExecutionResult failure(String errorMessage) {
      return new ScriptExecutionResult(false, errorMessage, 0);
    }

    public static ScriptExecutionResult timeout() {
      return new ScriptExecutionResult(false, "Script execution timed out", 0);
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
        return ScriptExecutionResult.timeout();
      }

      long durationMs = System.currentTimeMillis() - startTime;

      if (scriptSuccess.get()) {
        logger.info("Script completed successfully in {}ms", durationMs);
        return ScriptExecutionResult.success(durationMs);
      } else {
        logger.warn("Script failed: {}", errorMessage.get());
        return ScriptExecutionResult.failure(errorMessage.get());
      }

    } catch (Exception e) {
      logger.error("Failed to start script: {}", scriptName, e);
      return ScriptExecutionResult.failure(e.getMessage());
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
