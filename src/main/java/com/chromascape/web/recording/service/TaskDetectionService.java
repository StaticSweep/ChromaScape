package com.chromascape.web.recording.service;

import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.config.TaskType;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskDetectionService {

  private static final Logger log = LoggerFactory.getLogger(TaskDetectionService.class);

  private final RecordingService recordingService;
  private final TaskRecordingPreferenceService taskPreferenceService;
  private final RecordingSessionRepository recordingSessionRepository;
  private TaskType currentTask = null;

  public TaskDetectionService(
      RecordingService recordingService,
      TaskRecordingPreferenceService taskPreferenceService,
      RecordingSessionRepository recordingSessionRepository) {
    this.recordingService = recordingService;
    this.taskPreferenceService = taskPreferenceService;
    this.recordingSessionRepository = recordingSessionRepository;
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
    if (taskPreferenceService.isTaskEnabled(taskType)) {
      log.info("Auto-starting recording for task: {}", taskType);
      String recordingId =
          recordingService.startRecordingAuto(
              taskType.getDisplayName(), RecordingTriggerSource.TASK_AUTO);
      currentTask = taskType;

      // Create RecordingSession entity for auto-start recording
      if (recordingId != null) {
        LocalDateTime startTime = recordingService.getRecordingStartTimeAsLocalDateTime();

        if (startTime != null) {
          if (recordingSessionRepository.findByRecordingId(recordingId).isEmpty()) {
            RecordingSession session =
                new RecordingSession(
                    recordingId,
                    "auto-detected", // Account name not available in this context
                    taskType.getDisplayName(),
                    startTime,
                    RecordingSession.SessionTrigger.AUTO_START,
                    null);
            recordingSessionRepository.save(session);
            log.info("Created RecordingSession for auto-start: {}", recordingId);
          } else {
            log.debug("RecordingSession already exists for {}, skipping insert", recordingId);
          }
        } else {
          log.warn(
              "Could not create RecordingSession for auto-start {}: start time unavailable",
              recordingId);
        }
      }
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
