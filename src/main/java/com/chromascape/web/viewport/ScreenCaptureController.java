package com.chromascape.web.viewport;

import com.chromascape.web.viewport.service.ScreenCaptureService;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API controller for screen capture control.
 *
 * <p>Provides endpoints for pausing, resuming, and checking the status of the continuous screen
 * capture service.
 */
@RestController
@RequestMapping("/api/screencapture")
public class ScreenCaptureController {

  private static final Logger logger = LogManager.getLogger(ScreenCaptureController.class);

  private final ScreenCaptureService screenCaptureService;

  @Autowired
  public ScreenCaptureController(ScreenCaptureService screenCaptureService) {
    this.screenCaptureService = screenCaptureService;
  }

  /**
   * Returns the current status of the screen capture service.
   *
   * @return JSON object containing the service status
   */
  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> getStatus() {
    return ResponseEntity.ok(
        Map.of(
            "running",
            screenCaptureService.isRunning(),
            "paused",
            screenCaptureService.isPaused()));
  }

  /**
   * Pauses the screen capture service.
   *
   * <p>The service continues running but stops capturing frames, reducing CPU usage.
   *
   * @return Success response
   */
  @PostMapping("/pause")
  public ResponseEntity<Map<String, String>> pause() {
    screenCaptureService.pause();
    logger.info("Screen capture paused via API");
    return ResponseEntity.ok(Map.of("status", "paused"));
  }

  /**
   * Resumes the screen capture service.
   *
   * @return Success response
   */
  @PostMapping("/resume")
  public ResponseEntity<Map<String, String>> resume() {
    screenCaptureService.resume();
    logger.info("Screen capture resumed via API");
    return ResponseEntity.ok(Map.of("status", "resumed"));
  }
}
