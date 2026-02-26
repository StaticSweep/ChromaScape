package com.chromascape.utils.core.screen.window;

import java.awt.Rectangle;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API controller for window selection operations.
 *
 * <p>Provides endpoints for listing available RuneLite windows and selecting which window to
 * capture when multiple instances are running.
 */
@RestController
@RequestMapping("/api/windows")
public class WindowController {

  private static final Logger logger = LogManager.getLogger(WindowController.class);

  private final WindowSelectionService windowSelectionService;

  @Autowired
  public WindowController(WindowSelectionService windowSelectionService) {
    this.windowSelectionService = windowSelectionService;
  }

  /**
   * Returns a list of all detected RuneLite game client windows.
   *
   * <p>Filters out inspector windows, IDE windows, and other non-game Java windows.
   *
   * @return JSON array of window information
   */
  @GetMapping
  public ResponseEntity<List<Map<String, Object>>> listWindows() {
    List<WindowInfo> windows = WindowHandler.getAllTargetWindows();

    // Filter to only show game client windows (using process verification)
    List<WindowInfo> filtered =
        windows.stream()
            .filter(w -> isGameClient(w.getTitle(), w.getBounds(), w.getPid()))
            .toList();

    // Debug: Show windows before deduplication
    System.err.println("[WINDOW DEBUG] Before deduplication: " + filtered.size() + " windows");
    for (WindowInfo w : filtered) {
      System.err.println(
          "[WINDOW DEBUG]   - "
              + w.getTitle()
              + " (PID: "
              + w.getPid()
              + ", ID: "
              + w.getId()
              + ", bounds: "
              + w.getBounds().width
              + "x"
              + w.getBounds().height
              + ")");
    }

    // Deduplicate by PID (keep only largest window per process)
    List<WindowInfo> deduplicated = WindowHandler.deduplicateWindowsByPid(filtered);

    // Debug: Show windows after deduplication
    System.err.println("[WINDOW DEBUG] After deduplication: " + deduplicated.size() + " windows");
    for (WindowInfo w : deduplicated) {
      System.err.println(
          "[WINDOW DEBUG]   - "
              + w.getTitle()
              + " (PID: "
              + w.getPid()
              + ", ID: "
              + w.getId()
              + ")");
    }

    List<Map<String, Object>> response =
        deduplicated.stream()
            .map(
                window -> {
                  Map<String, Object> map = new HashMap<>();
                  map.put("id", window.getId());
                  map.put("title", window.getTitle());
                  map.put("accountName", window.getAccountName());

                  // Convert bounds to map
                  Rectangle bounds = window.getBounds();
                  Map<String, Object> boundsMap = new HashMap<>();
                  boundsMap.put("x", bounds.x);
                  boundsMap.put("y", bounds.y);
                  boundsMap.put("width", bounds.width);
                  boundsMap.put("height", bounds.height);
                  map.put("bounds", boundsMap);

                  return map;
                })
            .toList();

    return ResponseEntity.ok(response);
  }

  /**
   * Checks if a window represents a game client window.
   *
   * <p>Uses process-based verification to distinguish between standalone Java game clients
   * (RuneLite, Leeuwbot, VitaLite) and IDE-embedded Java processes. Also filters by size and title.
   *
   * @param title the window title to check
   * @param bounds the window bounds to check for size filtering
   * @param pid the process ID to verify it's a standalone Java process
   * @return true if this is a game client window
   */
  private boolean isGameClient(String title, Rectangle bounds, int pid) {
    if (title == null) return false;
    if (bounds == null) return false;

    // Filter out small utility windows (main game windows are typically larger)
    if (bounds.width < 400 || bounds.height < 400) {
      return false;
    }

    // Verify it's a standalone Java process (not running from an IDE)
    if (!ProcessVerificationUtil.isStandaloneJavaProcess(pid)) {
      return false;
    }

    // Case-insensitive title check for supported game clients
    String titleLower = title.toLowerCase();
    if (!titleLower.contains("runelite")
        && !titleLower.contains("leeuwbot")
        && !titleLower.contains("vitalite")) {
      return false;
    }

    // Exclude inspector/shell windows
    if (titleLower.contains("inspector") || titleLower.contains("shell")) {
      return false;
    }

    return true;
  }

  /**
   * Returns the currently selected window.
   *
   * @return JSON object representing the selected window, or 204 No Content if none selected
   */
  @GetMapping("/current")
  public ResponseEntity<Map<String, Object>> getCurrentWindow() {
    WindowInfo selected = windowSelectionService.getSelectedWindow();

    if (selected == null) {
      return ResponseEntity.noContent().build();
    }

    Map<String, Object> response = new HashMap<>();
    response.put("id", selected.getId());
    response.put("title", selected.getTitle());
    response.put("accountName", selected.getAccountName());

    Rectangle bounds = selected.getBounds();
    Map<String, Object> boundsMap = new HashMap<>();
    boundsMap.put("x", bounds.x);
    boundsMap.put("y", bounds.y);
    boundsMap.put("width", bounds.width);
    boundsMap.put("height", bounds.height);
    response.put("bounds", boundsMap);

    return ResponseEntity.ok(response);
  }

  /**
   * Selects a window by its ID.
   *
   * @param request Request body containing the window ID
   * @return Success response
   */
  @PostMapping("/select")
  public ResponseEntity<Map<String, String>> selectWindow(
      @RequestBody Map<String, String> request) {
    String windowId = request.get("id");

    System.err.println("[WINDOW API] Received select request for window ID: '" + windowId + "'");

    if (windowId == null || windowId.trim().isEmpty()) {
      // Clear selection
      windowSelectionService.clearSelection();
      logger.info("Window selection cleared via API");
      return ResponseEntity.ok(Map.of("status", "cleared"));
    }

    // Find the window with the given ID from filtered game clients
    List<WindowInfo> windows = WindowHandler.getAllTargetWindows();
    List<WindowInfo> filtered =
        windows.stream()
            .filter(w -> isGameClient(w.getTitle(), w.getBounds(), w.getPid()))
            .toList();

    // Deduplicate by PID (keep only largest window per process)
    List<WindowInfo> deduplicated = WindowHandler.deduplicateWindowsByPid(filtered);

    System.err.println(
        "[WINDOW API] Found " + deduplicated.size() + " game client windows after deduplication");

    WindowInfo selectedWindow =
        deduplicated.stream().filter(w -> w.getId().equals(windowId)).findFirst().orElse(null);

    if (selectedWindow == null) {
      System.err.println("[WINDOW API] ERROR: Window with ID '" + windowId + "' not found!");
      return ResponseEntity.notFound().build();
    }

    windowSelectionService.setSelectedWindow(selectedWindow);
    logger.info("Window selected via API: {} (ID: {})", selectedWindow.getTitle(), windowId);

    System.err.println(
        "[WINDOW API] Successfully selected window: "
            + selectedWindow.getTitle()
            + " (HWND: "
            + selectedWindow.getHwnd()
            + ")");

    return ResponseEntity.ok(
        Map.of("status", "selected", "id", windowId, "title", selectedWindow.getTitle()));
  }

  /**
   * Clears the current window selection.
   *
   * @return Success response
   */
  @PostMapping("/clear")
  public ResponseEntity<Map<String, String>> clearSelection() {
    windowSelectionService.clearSelection();
    logger.info("Window selection cleared via API");
    return ResponseEntity.ok(Map.of("status", "cleared"));
  }
}
