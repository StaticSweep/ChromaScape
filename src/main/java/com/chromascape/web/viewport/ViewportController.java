package com.chromascape.web.viewport;

import com.chromascape.web.recording.service.RecordingService;
import com.chromascape.web.viewport.config.UnifiedCaptureConfig;
import com.chromascape.web.viewport.service.UnifiedFrameCaptureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for viewport operations.
 *
 * <p>Provides endpoints for controlling the viewport frame capture, including runtime FPS
 * adjustment.
 */
@RestController
@RequestMapping("/api/viewport")
public class ViewportController {

  @Autowired private UnifiedCaptureConfig captureConfig;
  @Autowired private UnifiedFrameCaptureService captureService;
  @Autowired private RecordingService recordingService;

  /**
   * Gets the current viewport capture FPS.
   *
   * @return The current FPS setting, or 400 if unified capture is disabled
   */
  @GetMapping("/fps")
  public ResponseEntity<Integer> getCurrentFps() {
    if (!captureConfig.isEnabled()) {
      return ResponseEntity.badRequest().body(null);
    }
    return ResponseEntity.ok(captureConfig.getFps());
  }

  /**
   * Sets the viewport capture FPS at runtime.
   *
   * <p>Validates the FPS range (1-60) and ensures no recording is active before changing the FPS.
   * Changing FPS during recording would desync the video encoder.
   *
   * @param fps The new FPS value (1-60)
   * @return Success message, or error response with explanation
   */
  @PostMapping("/fps")
  public ResponseEntity<String> setFps(@RequestParam int fps) {
    // Check if unified capture is enabled
    if (!captureConfig.isEnabled()) {
      return ResponseEntity.badRequest().body("Unified capture is disabled. Cannot change FPS.");
    }

    // Check if recording is active - changing FPS would desync the video
    if (recordingService.isRecording()) {
      return ResponseEntity.status(409)
          .body("Cannot change FPS while recording is active. Stop recording first.");
    }

    try {
      captureConfig.setFps(fps); // Validates 1-60 range
      captureService.restartCapture(); // Restart with new FPS
      return ResponseEntity.ok("FPS updated to " + fps);
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }
}
