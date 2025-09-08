package com.chromascape.base;

import static java.time.temporal.ChronoUnit.MINUTES;

import com.chromascape.controller.Controller;
import com.chromascape.utils.core.runtime.HotkeyListener;
import com.chromascape.utils.core.runtime.ScriptStoppedException;
import com.chromascape.web.logs.LogService;
import java.time.LocalTime;

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
  protected final LogService logger;
  private final HotkeyListener hotkeyListener;
  private boolean running = true;
  private LocalTime startTime;

  /**
   * Constructs a BaseScript.
   *
   * @param isFixed whether the client UI is fixed or resizable
   * @param duration the total runtime of the script in minutes
   * @param logger the logging service for recording events and progress
   */
  public BaseScript(final boolean isFixed, final int duration, final LogService logger) {
    controller = new Controller(isFixed, logger);
    this.duration = duration;
    this.logger = logger;
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
    logger.addLog("Starting. Script will run for " + duration + " minutes.");
    controller.init();
    hotkeyListener.start();

    try {
      while (running && LocalTime.now().isBefore(endTime)) {
        if (Thread.currentThread().isInterrupted()) {
          logger.addLog("Thread interrupted, exiting.");
          break;
        }
        try {
          cycle();
        } catch (ScriptStoppedException e) {
          logger.addLog("Cycle interrupted: " + e.getMessage());
          break;
        } catch (Exception e) {
          logger.addLog("Exception in cycle: " + e.getMessage());
          break;
        }
      }
    } finally {
      logger.addLog("Stopping and cleaning up.");
      controller.shutdown();
    }
    logger.addLog("Finished running script.");
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
    logger.addLog("Stop requested");
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
  public int getProgressPercent() {
    if (startTime == null) {
      return 0;
    }
    LocalTime now = LocalTime.now();
    long minsDone = MINUTES.between(startTime, now);
    long clamped = Math.min(minsDone, duration);
    return (int) (clamped * 100 / duration);
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
