package com.chromascape.controller;

import com.chromascape.utils.core.input.keyboard.VirtualKeyboardUtils;
import com.chromascape.utils.core.input.mouse.VirtualMouseUtils;
import com.chromascape.utils.core.input.remoteinput.Kinput;
import com.chromascape.utils.core.screen.window.ScreenManager;
import com.chromascape.utils.core.screen.window.WindowHandler;
import com.chromascape.utils.domain.zones.ZoneManager;
import com.chromascape.web.logs.LogService;

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
  private final boolean isFixed;

  private Kinput kinput;
  private VirtualMouseUtils virtualMouseUtils;
  private VirtualKeyboardUtils virtualKeyboardUtils;
  private ZoneManager zoneManager;
  private final LogService logger;

  /**
   * Constructs a new Controller instance.
   *
   * @param isFixed Indicates whether the user's client is resizable or fixed. The user selects this
   *     in the UI.
   * @param logger The logger to record lifecycle events and errors.
   */
  public Controller(boolean isFixed, LogService logger) {
    this.state = ControllerState.STOPPED;
    this.isFixed = isFixed;
    this.logger = logger;
  }

  /**
   * Initializes and starts the controller, setting up all core utilities needed for the bot to
   * operate, including input devices, hotkey listener, screen capture, and zone management.
   *
   * <p>This method queries the target client window, configures input hooks, and prepares the
   * internal state for running.
   */
  public void init() {
    // Obtain process ID of the target window to initialize input injection
    kinput = new Kinput(WindowHandler.getPid(WindowHandler.getTargetWindow()));

    // Ensure the target window is focused for input simulation
    ScreenManager.focusWindow();
    boolean isFullscreen = ScreenManager.isWindowFullscreen();

    // Initialize virtual input utilities with current window bounds and fullscreen status
    virtualMouseUtils =
        new VirtualMouseUtils(kinput, ScreenManager.getWindowBounds(), isFullscreen);
    virtualKeyboardUtils = new VirtualKeyboardUtils(kinput);

    // Initialize zone management with fixed mode option
    zoneManager = new ZoneManager(isFixed);

    state = ControllerState.RUNNING;
  }

  /**
   * Shuts down the controller and releases all resources.
   *
   * <p>This stops input injection, stops hotkey listening, and prevents further access to stateful
   * utilities until re-initialized.
   */
  public void shutdown() {
    state = ControllerState.STOPPED;
    kinput.destroy();
    logger.addLog("Shutting down");
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
   * Checks that the controller is currently running before allowing access to any stateful utility,
   * logging and throwing an exception if not.
   *
   * @param component The name of the utility being accessed.
   * @throws IllegalStateException if the controller is not running.
   */
  private void assertRunning(String component) {
    if (state != ControllerState.RUNNING) {
      if (logger != null) {
        logger.addLog(component + " accessed while bot is not running.");
      }
      throw new IllegalStateException(component + " accessed while bot is not running.");
    }
  }
}
