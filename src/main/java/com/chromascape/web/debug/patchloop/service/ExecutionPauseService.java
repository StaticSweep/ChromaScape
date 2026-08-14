package com.chromascape.web.debug.patchloop.service;

import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for managing execution pause/resume state.
 *
 * <p>Provides a shared flag mechanism for coordinating pause state between HTTP controllers (which
 * set pause state via API) and graph executor (which checks pause state during loop). Uses
 * in-memory ConcurrentHashMap for thread-safe pause flag storage.
 */
@Service
public class ExecutionPauseService {

  private static final Logger logger = LoggerFactory.getLogger(ExecutionPauseService.class);

  /** Map of execution ID to pause state. */
  private final ConcurrentHashMap<String, Boolean> pauseFlags = new ConcurrentHashMap<>();

  /**
   * Checks if an execution is currently paused.
   *
   * @param executionId the execution ID to check
   * @return true if paused, false otherwise
   */
  public boolean isPaused(String executionId) {
    Boolean paused = pauseFlags.get(executionId);
    return paused != null && paused;
  }

  /**
   * Sets the pause state for an execution.
   *
   * @param executionId the execution ID
   * @param paused true to pause, false to resume
   */
  public void setPaused(String executionId, boolean paused) {
    if (paused) {
      pauseFlags.put(executionId, true);
      logger.debug("Execution {} paused", executionId);
    } else {
      pauseFlags.remove(executionId);
      logger.debug("Execution {} resumed", executionId);
    }
  }

  /**
   * Clears the pause state for an execution.
   *
   * <p>Called when execution completes or is cancelled.
   *
   * @param executionId the execution ID to clear
   */
  public void clearPaused(String executionId) {
    pauseFlags.remove(executionId);
  }

  /**
   * Gets the count of currently paused executions.
   *
   * @return number of paused executions
   */
  public int getPausedCount() {
    int count = 0;
    for (Boolean paused : pauseFlags.values()) {
      if (paused) {
        count++;
      }
    }
    return count;
  }
}
