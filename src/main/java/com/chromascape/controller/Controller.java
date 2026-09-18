package com.chromascape.controller;

import com.chromascape.utils.core.input.TargetInputRuntime;
import com.chromascape.utils.core.input.TargetInputSession;
import com.chromascape.utils.core.input.keyboard.VirtualKeyboardUtils;
import com.chromascape.utils.core.input.mouse.VirtualMouseUtils;
import com.chromascape.utils.domain.ocr.Ocr;
import com.chromascape.utils.domain.walker.Walker;
import com.chromascape.utils.domain.zones.ZoneManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Coordinates script utilities and one explicitly selected target input session. */
public class Controller {
  private enum ControllerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING
  }

  private static final Logger logger = LogManager.getLogger(Controller.class);

  private ControllerState state = ControllerState.STOPPED;
  private TargetInputSession inputSession;
  private VirtualMouseUtils virtualMouseUtils;
  private VirtualKeyboardUtils virtualKeyboardUtils;
  private ZoneManager zoneManager;
  private Walker walker;

  /** Constructs a stopped controller. */
  public Controller() {}

  /** Atomically initializes the selected input session and dependent utilities. */
  public synchronized void init() {
    if (state != ControllerState.STOPPED) {
      throw new IllegalStateException("Controller is already initialized");
    }
    state = ControllerState.STARTING;
    TargetInputSession opened = null;
    VirtualMouseUtils mouse = null;
    VirtualKeyboardUtils keyboard = null;
    try {
      preloadFonts();
      opened = TargetInputRuntime.factory().openSelectedSession();
      mouse = new VirtualMouseUtils(opened);
      keyboard = new VirtualKeyboardUtils(opened);
      ZoneManager zones = new ZoneManager();
      zones.getGameView();

      inputSession = opened;
      virtualMouseUtils = mouse;
      virtualKeyboardUtils = keyboard;
      zoneManager = zones;
      walker = new Walker(this);
      state = ControllerState.RUNNING;
      logger.info("Controller State: {}", state);
    } catch (RuntimeException failure) {
      closeAfterFailure(mouse, failure);
      closeAfterFailure(keyboard, failure);
      closeAfterFailure(opened, failure);
      clearResources();
      state = ControllerState.STOPPED;
      throw failure;
    }
  }

  private void preloadFonts() {
    try {
      Ocr.loadFont("Plain 11");
      Ocr.loadFont("Plain 12");
      Ocr.loadFont("Bold 12");
    } catch (Exception failure) {
      logger.warn("Failed to pre-load fonts during init: {}", failure.getMessage());
    }
  }

  private static void closeAfterFailure(AutoCloseable resource, RuntimeException original) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Exception cleanupFailure) {
      original.addSuppressed(cleanupFailure);
    }
  }

  /** Idempotently stops utilities and closes the shared input session. */
  public synchronized void shutdown() {
    if (state == ControllerState.STOPPED || state == ControllerState.STOPPING) {
      return;
    }
    state = ControllerState.STOPPING;
    RuntimeException failure = null;
    failure = closeForShutdown(virtualMouseUtils, failure);
    failure = closeForShutdown(virtualKeyboardUtils, failure);
    failure = closeForShutdown(inputSession, failure);
    clearResources();
    state = ControllerState.STOPPED;
    logger.info("Shutting down");
    if (failure != null) {
      throw failure;
    }
  }

  private static RuntimeException closeForShutdown(
      AutoCloseable resource, RuntimeException accumulated) {
    if (resource == null) {
      return accumulated;
    }
    try {
      resource.close();
    } catch (Exception closeFailure) {
      RuntimeException runtimeFailure =
          closeFailure instanceof RuntimeException runtime
              ? runtime
              : new IllegalStateException("Resource cleanup failed", closeFailure);
      if (accumulated == null) {
        return runtimeFailure;
      }
      accumulated.addSuppressed(runtimeFailure);
    }
    return accumulated;
  }

  private void clearResources() {
    inputSession = null;
    virtualMouseUtils = null;
    virtualKeyboardUtils = null;
    zoneManager = null;
    walker = null;
  }

  /** Schedules deletion of Windows KInput DLLs after native cleanup. */
  public boolean killKinput() {
    if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
      return false;
    }
    try {
      String distPath = new java.io.File("build/dist").getAbsolutePath();
      String ctrl = "\"" + distPath + "\\KInputCtrl.dll\"";
      String input = "\"" + distPath + "\\KInput.dll\"";
      new ProcessBuilder(
              "cmd.exe",
              "/c",
              "start",
              "/MIN",
              "cmd.exe",
              "/c",
              "timeout /t 2 /nobreak > NUL & del /f /q " + ctrl + " " + input)
          .start();
      return true;
    } catch (Exception failure) {
      logger.error("Failed to schedule Windows Kinput cleanup: {}", failure.getMessage());
      return false;
    }
  }

  /** Returns the active mouse utility. */
  public VirtualMouseUtils mouse() {
    assertRunning("VirtualMouseUtils");
    return virtualMouseUtils;
  }

  /** Returns the active keyboard utility. */
  public VirtualKeyboardUtils keyboard() {
    assertRunning("VirtualKeyboardUtils");
    return virtualKeyboardUtils;
  }

  /** Returns the active zone manager. */
  public ZoneManager zones() {
    assertRunning("ZoneManager");
    return zoneManager;
  }

  /** Returns the active walker. */
  public Walker walker() {
    assertRunning("Walker");
    return walker;
  }

  private void assertRunning(String component) {
    if (state != ControllerState.RUNNING) {
      throw new IllegalStateException(component + " accessed while bot is not running.");
    }
  }
}
