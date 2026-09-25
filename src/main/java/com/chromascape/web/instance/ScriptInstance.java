package com.chromascape.web.instance;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.statistics.StatisticsManager;
import com.chromascape.web.recording.config.TaskType;
import com.chromascape.web.recording.service.TaskDetectionService;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Manages the lifecycle of a script instance.
 *
 * <p>This class dynamically loads and instantiates a script class based on the provided
 * configuration, runs the script in its own thread, and provides control methods to start and stop
 * the script execution.
 */
public class ScriptInstance {

  private final BaseScript instance;
  private final String scriptName;
  private volatile Thread thread;
  private final WebSocketStateHandler stateHandler;
  private final TaskDetectionService taskDetectionService;
  private volatile long tickStartTime = 0;
  private final String taskPlanId;
  private final String taskItemId;

  /**
   * Constructs a ScriptInstance by dynamically loading the script class specified in the config.
   *
   * @param config the RunConfig containing script name
   * @throws NoSuchMethodException if the expected constructor is not found
   * @throws ClassNotFoundException if the script class cannot be found
   * @throws InvocationTargetException if the constructor throws an exception
   * @throws InstantiationException if the class is abstract or an interface
   * @throws IllegalAccessException if the constructor is not accessible
   */
  public ScriptInstance(
      RunConfig config,
      WebSocketStateHandler stateHandler,
      TaskDetectionService taskDetectionService)
      throws NoSuchMethodException,
          ClassNotFoundException,
          InvocationTargetException,
          InstantiationException,
          IllegalAccessException {
    this(config, stateHandler, taskDetectionService, null, null);
  }

  /**
   * Constructs a ScriptInstance with task plan context.
   *
   * @param config the RunConfig containing script name
   * @param taskPlanId optional task plan ID for plan-linked execution
   * @param taskItemId optional task item ID for plan-linked execution
   */
  public ScriptInstance(
      RunConfig config,
      WebSocketStateHandler stateHandler,
      TaskDetectionService taskDetectionService,
      String taskPlanId,
      String taskItemId)
      throws NoSuchMethodException,
          ClassNotFoundException,
          InvocationTargetException,
          InstantiationException,
          IllegalAccessException {
    this.stateHandler = stateHandler;
    this.taskDetectionService = taskDetectionService;
    this.taskPlanId = taskPlanId;
    this.taskItemId = taskItemId;

    String fileName = config.script();
    String className = fileName.replace(".java", "").replace("/", ".");
    this.scriptName = className;

    Class<?> script = Class.forName("com.chromascape.scripts." + className);
    Constructor<?> constructor = script.getDeclaredConstructor();
    instance = (BaseScript) constructor.newInstance();
  }

  /**
   * Starts the script execution in a new thread.
   *
   * <p>Resets the statistics via {@link StatisticsManager#reset()} before running, so that each run
   * starts with fresh metrics.
   *
   * <p>Also broadcasts a {@code true} state to clients.
   */
  public void start() {
    thread =
        new Thread(
            () -> {
              stateHandler.broadcast(true);
              tickStartTime = System.currentTimeMillis();
              StatisticsManager.reset();

              // Detect task type and auto-record if enabled. Plan-linked runs use the
              // plan-task path; otherwise heuristic detection from the script name is used.
              if (taskDetectionService != null) {
                dispatchTaskDetection(taskDetectionService, taskPlanId, taskItemId, scriptName);
              }

              try {
                instance.run();
              } finally {
                StatisticsManager.stop();
                if (taskDetectionService != null) {
                  taskDetectionService.onTaskEnded();
                }
                stateHandler.broadcast(false);
              }
            });
    thread.start();
  }

  /**
   * Dispatches the appropriate task-detection callback based on whether plan context is present.
   *
   * <p>Plan-linked runs (non-blank {@code taskPlanId}) use {@link
   * TaskDetectionService#onPlanTaskDetected}; heuristic runs use {@link
   * TaskDetectionService#onTaskDetected}. Extracted as a static helper so it can be unit-tested
   * without constructing a {@link BaseScript} (which binds native input/hook resources).
   *
   * @param taskDetectionService the detection service to dispatch to
   * @param taskPlanId optional task plan ID; non-blank triggers the plan path
   * @param taskItemId optional task plan item ID
   * @param scriptName the script name (used for heuristic detection and logging)
   */
  static void dispatchTaskDetection(
      TaskDetectionService taskDetectionService,
      String taskPlanId,
      String taskItemId,
      String scriptName) {
    if (taskPlanId != null && !taskPlanId.isBlank()) {
      taskDetectionService.onPlanTaskDetected(taskPlanId, taskItemId, scriptName);
    } else {
      TaskType taskType = detectTaskFromScriptName(scriptName);
      taskDetectionService.onTaskDetected(taskType, scriptName);
    }
  }

  /** Simple task detection based on script name */
  private static TaskType detectTaskFromScriptName(String scriptName) {
    String lower = scriptName.toLowerCase();
    if (lower.contains("combat") || lower.contains("fight") || lower.contains("kill")) {
      return TaskType.COMBAT;
    } else if (lower.contains("woodcut") || lower.contains("chop")) {
      return TaskType.WOODCUTTING;
    } else if (lower.contains("mining") || lower.contains("ore")) {
      return TaskType.MINING;
    } else if (lower.contains("fish")) {
      return TaskType.FISHING;
    } else if (lower.contains("cook")) {
      return TaskType.COOKING;
    } else if (lower.contains("smith")) {
      return TaskType.SMITHING;
    } else if (lower.contains("craft")) {
      return TaskType.CRAFTING;
    } else if (lower.contains("thiev")) {
      return TaskType.THIEVING;
    } else if (lower.contains("farm")) {
      return TaskType.FARMING;
    } else if (lower.contains("quest")) {
      return TaskType.QUESTING;
    } else if (lower.contains("boss") || lower.contains("raid")) {
      return TaskType.BOSSING;
    }
    return TaskType.OTHER;
  }

  /**
   * Stops the script execution by requesting the script to stop, interrupting the running thread,
   * and waiting for it to terminate.
   *
   * <p>explicitly calls {@link StatisticsManager#stop()} to freeze metrics immediately. Also
   * broadcasts a {@code false} state to clients.
   */
  public void stop() {
    instance.stop();
    StatisticsManager.stop();
    if (thread != null) {
      thread.interrupt();
      try {
        thread.join();
      } catch (InterruptedException ignored) {
        // Thread join interrupted, ignore to proceed with shutdown
      }
    }
    stateHandler.broadcast(false);
  }

  /** Returns the most recent tick count, aligned to the cycle counter. */
  public int getCurrentTick() {
    return StatisticsManager.getCycles();
  }

  /** Returns the timestamp when ticking started for the current run. */
  public long getTickStartTime() {
    return tickStartTime;
  }

  /** Returns the script name for the running instance. */
  public String getScriptName() {
    return scriptName;
  }

  /** Returns whether the script thread is currently running. */
  public boolean isRunning() {
    return thread != null && thread.isAlive();
  }

  /** Returns the task plan ID, if this instance was started from a plan. */
  public String getTaskPlanId() {
    return taskPlanId;
  }

  /** Returns the task plan item ID, if this instance was started from a plan. */
  public String getTaskItemId() {
    return taskItemId;
  }
}
