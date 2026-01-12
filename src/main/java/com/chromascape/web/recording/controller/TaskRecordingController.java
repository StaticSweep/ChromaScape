package com.chromascape.web.recording.controller;

import com.chromascape.web.recording.config.TaskRecordingConfig;
import com.chromascape.web.recording.config.TaskType;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/recordings/tasks")
public class TaskRecordingController {

  private final TaskRecordingConfig config;

  public TaskRecordingController(TaskRecordingConfig config) {
    this.config = config;
  }

  /** Get all task toggle states */
  @GetMapping
  public Map<String, Boolean> getTaskStates() {
    return config.getEnabledTasks();
  }

  /** Update a specific task toggle */
  @PostMapping("/{taskType}")
  public void setTaskState(@PathVariable String taskType, @RequestParam boolean enabled) {
    try {
      TaskType type = TaskType.valueOf(taskType.toUpperCase());
      config.setTaskEnabled(type, enabled);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid task type: " + taskType);
    }
  }

  /** Reset to defaults */
  @PostMapping("/reset")
  public Map<String, Boolean> resetToDefaults() {
    config.setEnabledTasks(TaskRecordingConfig.getDefaultTasks());
    return config.getEnabledTasks();
  }
}
