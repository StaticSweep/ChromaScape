package com.chromascape.controller;

import com.chromascape.utils.core.input.keyboard.VirtualKeyboardUtils;
import com.chromascape.utils.core.input.mouse.VirtualMouseUtils;
import com.chromascape.utils.core.input.remoteinput.Kinput;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.core.screen.window.WindowHandler;
import com.chromascape.utils.domain.ocr.Ocr;
import com.chromascape.utils.domain.walker.Walker;
import com.chromascape.utils.domain.zones.ZoneManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The central controller managing the lifecycle and access to core stateful utilities for input
 * simulation, screen capture, zone management, and hotkey listening.
 *
 * <p>Responsible for initializing and shutting down resources and enforcing runtime state checks to
 * prevent access to utilities when inactive.
 *
 * <p>This class abstracts and coordinates lower-level modules required for automation scripts.
 */
public class Controller {

  /** Represents the current running state of the controller. */
  private enum ControllerState {
    STOPPED,
    RUNNING
  }

  private ControllerState state;

  private Kinput kinput;
  private VirtualMouseUtils virtualMouseUtils;
  private VirtualKeyboardUtils virtualKeyboardUtils;
  private ZoneManager zoneManager;
  private Walker walker;
  private static final Logger logger = LogManager.getLogger(Controller.class);

  /** Constructs a new Controller instance. */
  public Controller() {
    this.state = ControllerState.STOPPED;
  }

  /**
   * Initializes and starts the controller, setting up all core utilities needed for the bot to
   * operate, including input devices, hotkey listener, screen capture, and zone management.
   *
   * <p>This method queries the target client window, configures input hooks, and prepares the
   * internal state for running.
   */
  public void init() {
    logger.info("Setting up Font masks...");
    // Warmup: Pre-load common fonts
    try {
      Ocr.loadFont("Plain 11");
      Ocr.loadFont("Plain 12");
      Ocr.loadFont("Bold 12");
    } catch (Exception e) {
      logger.error("Failed to pre-load fonts during init: {}", e.getMessage());
    }

    logger.info("Setting up Remote Input Library...");
    // Obtain process ID of the target window to initialize input injection
    kinput = new Kinput(WindowHandler.getPid(WindowHandler.getTargetWindow()));

    // Initialize virtual input utilities with current window bounds and fullscreen status
    logger.info("Initialising mouse and keyboard utils...");
    virtualMouseUtils = new VirtualMouseUtils(kinput, ScreenManager.getWindowBounds());
    virtualKeyboardUtils = new VirtualKeyboardUtils(kinput);

    logger.info("Pre-loading and instantiating zones...");
    // Initialize zone management with fixed mode option
    zoneManager = new ZoneManager();
    // Initialise gameView instead of LazyLoading, to improve startup overhead
    zoneManager.getGameView();

    state = ControllerState.RUNNING;

    // Initialises a walker to provide the script with Walking functionality through the DAX API
    walker = new Walker(this);
    logger.info("Controller State: {}", state);
  }

  /**
   * Shuts down the controller and releases all resources.
   *
   * <p>This stops input injection, stops hotkey listening, and prevents further access to stateful
   * utilities until re-initialized.
   */
  public void shutdown() {
    mouse().getMouseOverlay().eraseOverlay();
    kinput.destroy();
    if (!killKinput()) {
      logger.warn("Kinput failed to destroy");
    }
    state = ControllerState.STOPPED;
    logger.info("Shutting down");
  }

  /**
   * Uses the command prompt to forcibly delete KInput.dll and KInputCtrl.dll from the build
   * directory. Effectively freeing up the program to rerun.
   *
   * @return {@code true} if successful, {@code false} if not.
   */
  public boolean killKinput() {
    try {
      String distPath = new java.io.File("build/dist").getAbsolutePath();
      String kInputCtrl = "\"" + distPath + "\\KInputCtrl.dll\"";
      String kInput = "\"" + distPath + "\\KInput.dll\"";

      // Wait 2s, then force delete files.
      // We use cmd /c start "" /B to ensure it runs detached/background if possible.
      // Using ProcessBuilder to avoid Runtime.exec deprecation.
      new ProcessBuilder(
              "cmd.exe",
              "/c",
              "start",
              "/MIN",
              "cmd.exe",
              "/c",
              "timeout /t 2 /nobreak > NUL & del /f /q " + kInputCtrl + " " + kInput)
          .start();

      logger.info("Scheduled forced Kinput DLL cleanup in 2 seconds.");
      return true;
    } catch (Exception e) {
      logger.error("Failed to schedule Kinput cleanup: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Provides access to the virtual mouse utility.
   *
   * @return The virtual mouse utility for simulated mouse actions.
   * @throws IllegalStateException if called while the controller is not running.
   */
  public VirtualMouseUtils mouse() {
    assertRunning("VirtualMouseUtils");
    return virtualMouseUtils;
  }

  /**
   * Provides access to the virtual keyboard utility.
   *
   * @return The virtual keyboard utility for simulated keyboard actions.
   * @throws IllegalStateException if called while the controller is not running.
   */
  public VirtualKeyboardUtils keyboard() {
    assertRunning("VirtualKeyboardUtils");
    return virtualKeyboardUtils;
  }

  /**
   * Provides access to the zone manager utility.
   *
   * <p>The ZoneManager maintains mappings of UI sub-zones to support interaction with different
   * client interface areas.
   *
   * @return The ZoneManager instance.
   * @throws IllegalStateException if called while the controller is not running.
   */
  public ZoneManager zones() {
    assertRunning("ZoneManager");
    return zoneManager;
  }

  /**
   * Provides access to the walker domain utility.
   *
   * @return The walker utility, to be able to pathfind in-game.
   */
  public Walker walker() {
    assertRunning("Walker");
    return walker;
  }

  /**
   * Checks that the controller is currently running before allowing access to any stateful utility,
   * logging and throwing an exception if not.
   *
   * @param component The name of the utility being accessed.
   * @throws IllegalStateException if the controller is not running.
   */
  private void assertRunning(String component) {
    if (state != ControllerState.RUNNING) {
      if (logger != null) {
        logger.info("{} accessed while bot is not running.", component);
      }
      throw new IllegalStateException(component + " accessed while bot is not running.");
    }
  }
}
