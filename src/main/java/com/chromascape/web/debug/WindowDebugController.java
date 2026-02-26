package com.chromascape.web.debug;

import com.chromascape.utils.core.screen.window.WindowHandler;
import com.chromascape.utils.core.screen.window.WindowInfo;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug controller for window enumeration and detection.
 *
 * <p>This controller provides endpoints to help diagnose issues with window detection, particularly
 * when ChromaScape is running under WSL and needs to access Windows APIs.
 */
@RestController
@RequestMapping("/api/debug/windows")
public class WindowDebugController {

  private static final Logger logger = LoggerFactory.getLogger(WindowDebugController.class);

  /** JNA interface for accessing low-level Win32 User32 functions. */
  public interface User32 extends com.sun.jna.win32.StdCallLibrary {
    User32 INSTANCE = Native.load("user32", User32.class);

    void EnumWindows(WNDENUMPROC lpEnumFunc, Pointer arg);

    void GetWindowTextA(HWND hwnd, byte[] lpString, int maxCount);

    void GetClassNameA(HWND hwnd, byte[] lpString, int maxCount);

    boolean IsWindowVisible(HWND hwnd);
  }

  /** Simple record to hold window information for debugging. */
  public record WindowDebugInfo(
      int index, String title, String className, boolean visible, String hwnd) {}

  /**
   * Enumerates all windows on the system for debugging purposes.
   *
   * <p>This endpoint returns ALL windows with their titles, class names, visibility status, and
   * handles. This helps diagnose what windows JNA can actually see from WSL.
   *
   * @return List of all windows with debug information
   */
  @GetMapping("/enumerate")
  public ResponseEntity<List<WindowDebugInfo>> enumerateAllWindows() {
    List<WindowDebugInfo> windows = new ArrayList<>();
    User32 user32 = User32.INSTANCE;
    AtomicInteger index = new AtomicInteger(1);

    logger.info("Enumerating all windows for debugging...");

    user32.EnumWindows(
        (hwnd, arg) -> {
          try {
            // Get window title
            byte[] titleBuffer = new byte[512];
            user32.GetWindowTextA(hwnd, titleBuffer, 512);
            String title = Native.toString(titleBuffer).trim();

            // Get window class name
            byte[] classBuffer = new byte[256];
            user32.GetClassNameA(hwnd, classBuffer, 256);
            String className = Native.toString(classBuffer).trim();

            // Check if window is visible
            boolean visible = user32.IsWindowVisible(hwnd);

            // Only include windows with titles or special classes
            if (!title.isEmpty() || !className.isEmpty()) {
              WindowDebugInfo info =
                  new WindowDebugInfo(
                      index.getAndIncrement(),
                      title.isEmpty() ? "<no title>" : title,
                      className.isEmpty() ? "<no class>" : className,
                      visible,
                      hwnd.toString());
              windows.add(info);

              logger.debug("Found window: {} - {} (visible: {})", title, className, visible);
            }
          } catch (Exception e) {
            logger.error("Error processing window: {}", e.getMessage());
          }
          return true; // continue enumeration
        },
        null);

    logger.info("Enumerated {} total windows", windows.size());

    // Also log matching windows
    List<WindowDebugInfo> matchingWindows =
        windows.stream()
            .filter(
                w ->
                    w.title().toLowerCase().contains("runelite")
                        || w.title().toLowerCase().contains("leeuwbot")
                        || w.title().toLowerCase().contains("vitalite")
                        || w.className().contains("Awt")
                        || w.className().contains("Sun"))
            .toList();

    logger.info("Found {} potential game windows", matchingWindows.size());
    matchingWindows.forEach(w -> logger.info("  - {} [{}]", w.title(), w.className()));

    return ResponseEntity.ok(windows);
  }

  /**
   * Gets the list of detected target windows (RuneLite, Leeuwbot, VitaLite).
   *
   * <p>This uses the existing WindowHandler.getAllTargetWindows() method and returns the results
   * with additional debug information.
   *
   * @return List of detected target windows
   */
  @GetMapping("/targets")
  public ResponseEntity<List<WindowInfo>> getTargetWindows() {
    logger.info("Getting target windows via WindowHandler.getAllTargetWindows()...");
    List<WindowInfo> windows = WindowHandler.getAllTargetWindows();
    logger.info("Found {} target windows", windows.size());
    return ResponseEntity.ok(windows);
  }

  /**
   * Tests the target window detection logic.
   *
   * <p>This endpoint calls WindowHandler.getTargetWindow() to see what window would be selected for
   * capture, and returns details about the result.
   *
   * @return Object containing the detected window or null if none found
   */
  @GetMapping("/test-detection")
  public ResponseEntity<Object> testWindowDetection() {
    logger.info("Testing WindowHandler.getTargetWindow()...");

    try {
      HWND targetWindow = WindowHandler.getTargetWindow();

      if (targetWindow != null) {
        logger.info("Target window found: {}", targetWindow);
        return ResponseEntity.ok(
            new DetectionResult(true, targetWindow.toString(), "Window found"));
      } else {
        logger.warn("No target window found!");
        return ResponseEntity.ok(
            new DetectionResult(
                false, null, "No target window found - is RuneLite/Leeuwbot/VitaLite running?"));
      }
    } catch (Exception e) {
      logger.error("Error during window detection: {}", e.getMessage(), e);
      return ResponseEntity.ok(new DetectionResult(false, null, "Error: " + e.getMessage()));
    }
  }

  /** Simple record to hold window detection test results. */
  public record DetectionResult(boolean success, String hwnd, String message) {}
}
