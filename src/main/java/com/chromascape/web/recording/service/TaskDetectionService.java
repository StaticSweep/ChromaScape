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
  private String currentTaskPlanId = null;
  private String currentTaskItemId = null;

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

  /** Returns the current task plan ID, if running from a plan. */
  public String getCurrentTaskPlanId() {
    return currentTaskPlanId;
  }

  /** Returns the current task item ID, if running from a plan. */
  public String getCurrentTaskItemId() {
    return currentTaskItemId;
  }

  /**
   * Called when a script is started with task plan context. Uses the plan-linked context before
   * falling back to heuristic detection.
   */
  public void onPlanTaskDetected(String taskPlanId, String taskItemId, String context) {
    log.info("Plan-linked task detected: plan={}, item={}, context={}", taskPlanId, taskItemId, context);
    this.currentTaskPlanId = taskPlanId;
    this.currentTaskItemId = taskItemId;

    // Auto-start recording for plan-linked tasks
    if (taskPreferenceService.isTaskEnabled(TaskType.OTHER)) {
      log.info("Auto-starting recording for plan task: {}/{}", taskPlanId, taskItemId);
      String recordingId =
          recordingService.startRecordingAuto(
              "plan-task", RecordingTriggerSource.TASK_AUTO);
      currentTask = TaskType.OTHER;

      if (recordingId != null) {
        LocalDateTime startTime = recordingService.getRecordingStartTimeAsLocalDateTime();
        if (startTime != null && recordingSessionRepository.findByRecordingId(recordingId).isEmpty()) {
          RecordingSession session =
              new RecordingSession(
                  recordingId,
                  "plan-task",
                  taskPlanId,
                  startTime,
                  RecordingSession.SessionTrigger.AUTO_START,
                  null);
          recordingSessionRepository.save(session);
        }
      }
    }
  }
}
