package com.chromascape.web.instance;

import com.chromascape.base.BaseScript;
import com.chromascape.utils.core.statistics.StatisticsManager;
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
  private volatile long tickStartTime = 0;

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
  public ScriptInstance(RunConfig config, WebSocketStateHandler stateHandler)
      throws NoSuchMethodException,
          ClassNotFoundException,
          InvocationTargetException,
          InstantiationException,
          IllegalAccessException {
    this.stateHandler = stateHandler;

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
              try {
                instance.run();
              } finally {
                StatisticsManager.stop();
                stateHandler.broadcast(false);
              }
            });
    thread.start();
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
}
