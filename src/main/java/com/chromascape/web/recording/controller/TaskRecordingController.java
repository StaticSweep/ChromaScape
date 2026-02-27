package com.chromascape.web.recording.controller;

import com.chromascape.web.recording.config.TaskType;
import com.chromascape.web.recording.service.TaskRecordingPreferenceService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for per-task recording toggle management.
 *
 * <p>Preferences are persisted in the database so they survive restarts. All task types default to
 * {@code false} (auto-recording off) on first startup.
 */
@RestController
@RequestMapping("/api/recordings/tasks")
public class TaskRecordingController {

  private final TaskRecordingPreferenceService preferenceService;

  public TaskRecordingController(TaskRecordingPreferenceService preferenceService) {
    this.preferenceService = preferenceService;
  }

  /**
   * Get all task toggle states.
   *
   * <p>Always returns a full map containing every {@link TaskType} key.
   */
  @GetMapping
  public Map<String, Boolean> getTaskStates() {
    return preferenceService.getEffectiveTaskMap();
  }

  /**
   * Enable or disable auto-recording for a specific task type.
   *
   * <p>Change is persisted immediately. Returns HTTP 400 if {@code taskType} is not a valid {@link
   * TaskType} value.
   */
  @PostMapping("/{taskType}")
  public ResponseEntity<?> setTaskState(
      @PathVariable String taskType, @RequestParam boolean enabled) {
    try {
      TaskType type = TaskType.valueOf(taskType.toUpperCase());
      preferenceService.setTaskEnabled(type, enabled);
      return ResponseEntity.ok(preferenceService.getEffectiveTaskMap());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(Map.of("error", "Invalid task type: " + taskType));
    }
  }

  /**
   * Reset all task auto-recording preferences to {@code false}.
   *
   * <p>Returns the updated map (all false).
   */
  @PostMapping("/reset")
  public Map<String, Boolean> resetAllOff() {
    return preferenceService.resetAllOff();
  }
}
