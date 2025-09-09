package com.chromascape.base;

import com.chromascape.controller.Controller;
import com.chromascape.utils.core.runtime.HotkeyListener;
import com.chromascape.utils.core.runtime.ScriptProgressPublisher;
import com.chromascape.utils.core.runtime.ScriptStoppedException;
import java.time.LocalTime;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Abstract base class representing a generic automation script with lifecycle management.
 *
 * <p>Provides a timed execution framework where the script runs cycles until a specified duration
 * elapses or the script is stopped externally.
 *
 * <p>Manages the underlying Controller instance and logging. Subclasses should override {@link
 * #cycle()} to define the script's main logic.
 */
public abstract class BaseScript {
  private final Controller controller;
  private final int duration; // Duration to run the script in minutes
  private static final Logger logger = LogManager.getLogger(BaseScript.class.getName());
  private final HotkeyListener hotkeyListener;
  private boolean running = true;
  private LocalTime startTime;

  /**
   * Constructs a BaseScript.
   *
   * @param isFixed whether the client UI is fixed or resizable
   * @param duration the total runtime of the script in minutes
   */
  public BaseScript(final boolean isFixed, final int duration) {
    controller = new Controller(isFixed);
    this.duration = duration;
    this.hotkeyListener = new HotkeyListener(this);
  }

  /**
   * Runs the script lifecycle.
   *
   * <p>Initializes the controller, logs start and stop events, then continuously invokes the {@link
   * #cycle()} method until the specified duration elapses or the script is stopped. Checks for
   * thread interruption and stops gracefully if detected.
   *
   * <p>This method blocks until completion.
   */
  public final void run() {
    startTime = LocalTime.now();
    LocalTime endTime = startTime.plusMinutes(duration);
    logger.info("Starting. Script will run for {} minutes.", duration);
    controller.init();
    hotkeyListener.start();

    try {
      while (running && LocalTime.now().isBefore(endTime)) {
        if (Thread.currentThread().isInterrupted()) {
          logger.info("Thread interrupted, exiting.");
          break;
        }
        try {
          cycle();
          ScriptProgressPublisher.updateProgress(getProgressPercent());
        } catch (ScriptStoppedException e) {
          logger.error("Cycle interrupted: {}", e.getMessage());
          break;
        } catch (Exception e) {
          logger.error("Exception in cycle: {}", e.getMessage());
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
   * Stops the script execution.
   *
   * <p>Can be called externally (e.g., via UI controls or programmatically) to request an immediate
   * stop of the running script via the {@code ScriptStoppedException}. If the script is already
   * stopped, this method does nothing.
   */
  public void stop() {
    if (!running) {
      return;
    }
    logger.info("Stop requested");
    running = false;
    hotkeyListener.stop();
    throw new ScriptStoppedException();
  }

  /**
   * Returns the progress of the script as a percentage of total duration completed.
   *
   * <p>Intended for UI or monitoring purposes to indicate how far through the execution period the
   * script currently is.
   *
   * @return an integer between 0 and 100 representing progress percent, or 0 if not started
   */
  private int getProgressPercent() {
    if (startTime == null) {
      return 0;
    }
    long secondsDone = java.time.Duration.between(startTime, LocalTime.now()).getSeconds();
    long totalSeconds = duration * 60L;
    long clamped = Math.min(secondsDone, totalSeconds);
    return (int) (clamped * 100 / totalSeconds);
  }

  /**
   * The core logic of the script.
   *
   * <p>This method is called repeatedly in a loop by {@link #run()} for the specified duration.
   * Subclasses must override this method to implement their specific bot behavior.
   *
   * <p>Note: This method is called synchronously on the running thread.
   */
  protected void cycle() {
    // override this
  }

  /**
   * Exposes the local controller to children of this class.
   *
   * @return The controller object.
   */
  protected Controller controller() {
    return controller;
  }
}
