package com.chromascape.base;

import com.chromascape.controller.Controller;
import com.chromascape.utils.core.runtime.ScriptStoppedException;
import com.chromascape.utils.core.state.BotState;
import com.chromascape.utils.core.state.StateManager;
import com.chromascape.utils.core.statistics.StatisticsManager;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Abstract base class representing a generic automation script with lifecycle management.
 *
 * <p>Provides a timed execution framework where the script runs cycles until the script is stopped
 * externally.
 *
 * <p>Manages the underlying Controller instance. Subclasses should override {@link #cycle()} to
 * define the script's main logic.
 */
public abstract class BaseScript {
  private final Controller controller;
  private static final Logger logger = LogManager.getLogger(BaseScript.class);
  private volatile boolean running = true;
  private Thread scriptThread;

  /** Constructs a BaseScript. */
  public BaseScript() {
    controller = new Controller();
  }

  /**
   * Runs the script lifecycle.
   *
   * <p>Initializes the controller, logs start and stop events, then continuously invokes the {@link
   * #cycle()} method until the script is stopped. Checks for thread interruption and stops
   * gracefully if detected.
   *
   * <p>This method blocks until completion.
   */
  public final void run() {
    scriptThread = Thread.currentThread();
    controller.init();
    StatisticsManager.reset();

    try {
      while (running) {
        StatisticsManager.incrementCycles();
        if (Thread.currentThread().isInterrupted()) {
          logger.info("Thread interrupted, exiting.");
          break;
        }
        try {
          cycle();
        } catch (ScriptStoppedException e) {
          logger.error("Cycle interrupted: {}", e.getMessage());
          break;
        } catch (Exception e) {
          StateManager.setState(BotState.ERROR);
          logger.error("Exception in cycle: {}, {}", e.getMessage(), e.getStackTrace());
          break;
        }
      }
    } finally {
      logger.info("Stopping and cleaning up.");
      controller.shutdown();
    }
    logger.info("Finished running script.");
  }

  /**
   * Stops the script execution by interrupting the script thread.
   *
   * <p>Can be called externally (e.g., via UI controls or programmatically) to request an immediate
   * stop of the running script. If the script is already stopped, this method does nothing.
   */
  public void stop() {
    if (!running) {
      return;
    }
    logger.info("Stop requested");
    running = false;

    // Interrupt the script thread instead of throwing exception
    if (scriptThread != null) {
      scriptThread.interrupt();
    }
  }

  /**
   * Pauses the current thread for the specified number of milliseconds.
   *
   * <p>If the sleep is interrupted, this method throws ScriptStoppedException to enable immediate
   * stopping.
   *
   * @param ms the duration to sleep in milliseconds
   * @throws ScriptStoppedException if the thread is interrupted during sleep
   */
  public static void waitMillis(long ms) {
    StateManager.setState(BotState.WAITING);
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // Restore interrupt status
      throw new ScriptStoppedException();
    }
  }

  /**
   * Pauses the current thread for a random duration between {@code min} and {@code max}
   * milliseconds (inclusive).
   *
   * <p>This method internally calls {@link #waitMillis(long)} with a randomly generated delay.
   *
   * @param min the minimum number of milliseconds to sleep (inclusive)
   * @param max the maximum number of milliseconds to sleep (inclusive)
   * @throws IllegalArgumentException if {@code min} is greater than {@code max}
   * @throws ScriptStoppedException if the thread is interrupted during sleep
   */
  public static void waitRandomMillis(long min, long max) throws ScriptStoppedException {
    if (min > max) {
      throw new IllegalArgumentException("min must be less than or equal to max");
    }
    waitMillis(ThreadLocalRandom.current().nextLong(min, max + 1));
  }

  /**
   * Checks if the current thread has been interrupted and throws ScriptStoppedException if so. Call
   * this method frequently in your cycle implementation, especially in loops.
   *
   * @throws ScriptStoppedException if the thread has been interrupted
   */
  public static void checkInterrupted() throws ScriptStoppedException {
    if (Thread.currentThread().isInterrupted()) {
      throw new ScriptStoppedException();
    }
  }

  /**
   * The core logic of the script.
   *
   * <p>This method is called repeatedly in a loop by {@link #run()} for the specified duration.
   * Subclasses must override this method to implement their specific bot behavior.
   *
   * <p>Note: This method is called synchronously on the running thread. Use the provided sleep
   * methods and call {@link #checkInterrupted()} frequently to enable immediate stopping.
   */
  protected void cycle() {
    // override this
  }

  /**
   * Exposes the local controller to children of this class.
   *
   * @return The controller object.
   */
  public Controller controller() {
    return controller;
  }
}
