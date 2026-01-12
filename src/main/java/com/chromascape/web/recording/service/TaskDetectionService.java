package com.chromascape.web.recording.service;

import com.chromascape.web.recording.config.TaskRecordingConfig;
import com.chromascape.web.recording.config.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskDetectionService {

  private static final Logger log = LoggerFactory.getLogger(TaskDetectionService.class);

  private final RecordingService recordingService;
  private final TaskRecordingConfig taskConfig;
  private TaskType currentTask = null;

  public TaskDetectionService(RecordingService recordingService, TaskRecordingConfig taskConfig) {
    this.recordingService = recordingService;
    this.taskConfig = taskConfig;
  }

  /**
   * Called when a task type is detected during gameplay. Starts recording if that task type is
   * enabled.
   */
  public void onTaskDetected(TaskType taskType, String context) {
    if (currentTask == taskType) {
      return; // Already recording this task
    }

    log.info("Task detected: {} - {}", taskType, context);

    // Stop previous recording if any
    if (currentTask != null && recordingService.isRecording()) {
      recordingService.stopRecording();
      log.info("Stopped recording for previous task: {}", currentTask);
    }

    // Start new recording if this task is enabled
    if (taskConfig.isTaskEnabled(taskType)) {
      log.info("Auto-starting recording for task: {}", taskType);
      recordingService.startRecordingAuto(taskType.getDisplayName());
      currentTask = taskType;
    } else {
      currentTask = null;
    }
  }

  /** Called when task ends or script stops. */
  public void onTaskEnded() {
    if (currentTask != null && recordingService.isRecording()) {
      recordingService.stopRecording();
      log.info("Stopped recording for task: {}", currentTask);
      currentTask = null;
    }
  }

  public TaskType getCurrentTask() {
    return currentTask;
  }
}
