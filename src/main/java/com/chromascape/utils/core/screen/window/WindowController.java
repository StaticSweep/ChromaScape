package com.chromascape.utils.core.screen.window;

import com.sun.jna.platform.win32.WinDef.HWND;
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
 * <p>Provides endpoints for listing available RuneLite windows and selecting which window
 * to capture when multiple instances are running.
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
   * Returns a list of all detected Java/AWT windows (including RuneLite).
   *
   * @return JSON array of window information
   */
  @GetMapping
  public ResponseEntity<List<Map<String, Object>>> listWindows() {
    List<WindowInfo> windows = WindowHandler.getAllJavaWindows();

    List<Map<String, Object>> response = windows.stream()
        .map(window -> {
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
   * Returns the currently selected window.
   *
   * @return JSON object representing the selected window, or 404 if none selected
   */
  @GetMapping("/current")
  public ResponseEntity<Map<String, Object>> getCurrentWindow() {
    WindowInfo selected = windowSelectionService.getSelectedWindow();

    if (selected == null) {
      return ResponseEntity.notFound().build();
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
  public ResponseEntity<Map<String, String>> selectWindow(@RequestBody Map<String, String> request) {
    String windowId = request.get("id");

    if (windowId == null || windowId.trim().isEmpty()) {
      // Clear selection
      windowSelectionService.clearSelection();
      logger.info("Window selection cleared via API");
      return ResponseEntity.ok(Map.of("status", "cleared"));
    }

    // Find the window with the given ID
    List<WindowInfo> windows = WindowHandler.getAllJavaWindows();
    WindowInfo selectedWindow = windows.stream()
        .filter(w -> w.getId().equals(windowId))
        .findFirst()
        .orElse(null);

    if (selectedWindow == null) {
      return ResponseEntity.notFound().build();
    }

    windowSelectionService.setSelectedWindow(selectedWindow);
    logger.info("Window selected via API: {} (ID: {})", selectedWindow.getTitle(), windowId);

    return ResponseEntity.ok(Map.of(
        "status", "selected",
        "id", windowId,
        "title", selectedWindow.getTitle()));
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
