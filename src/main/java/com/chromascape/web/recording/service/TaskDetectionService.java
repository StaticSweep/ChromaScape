package com.chromascape.web.recording.service;

import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.config.TaskType;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import java.io.File;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
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

    // Stop and finalize previous recording if any
    if (currentTask != null && recordingService.isRecording()) {
      finalizeCurrentRecording("previous task: " + currentTask);
    }

    // Start new recording if this task is enabled
    if (taskPreferenceService.isTaskEnabled(taskType)) {
      log.info("Auto-starting recording for task: {}", taskType);
      String recordingId =
          recordingService.startRecordingAuto(
              taskType.getDisplayName(), RecordingTriggerSource.TASK_AUTO);
      if (recordingId != null) {
        // Only mark the task active after a recording actually started, so a failed start
        // does not pin the task and block a later retry of the same task.
        currentTask = taskType;
        // Create RecordingSession entity for auto-start recording
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
      } else {
        // Recording failed to start; clear all task state so a later detection can retry.
        clearTaskState();
      }
    } else {
      // Task not enabled for recording; clear all task state so stale plan context does
      // not leak into a later fallback row.
      clearTaskState();
    }
  }

  /** Called when task ends or script stops. */
  public void onTaskEnded() {
    if (currentTask != null && recordingService.isRecording()) {
      finalizeCurrentRecording("task: " + currentTask);
    } else {
      // No active recording to finalize; still clear any stale task state so it does
      // not leak into the next recording.
      clearTaskState();
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
    log.info(
        "Plan-linked task detected: plan={}, item={}, context={}", taskPlanId, taskItemId, context);

    // If a previous recording is active, finalize it before starting a new one so the
    // metadata for the prior task is not lost.
    if (currentTask != null && recordingService.isRecording()) {
      finalizeCurrentRecording("previous plan task");
    }

    if (taskPreferenceService.isTaskEnabled(TaskType.OTHER)) {
      log.info("Auto-starting recording for plan task: {}/{}", taskPlanId, taskItemId);
      String recordingId =
          recordingService.startRecordingAuto("plan-task", RecordingTriggerSource.TASK_AUTO);
      if (recordingId != null) {
        // Only store plan context after a recording actually started, so a disabled or
        // failed start does not leave plan context that would later mis-classify a
        // normal-task fallback row as plan-linked.
        currentTaskPlanId = taskPlanId;
        currentTaskItemId = taskItemId;
        currentTask = TaskType.OTHER;

        LocalDateTime startTime = recordingService.getRecordingStartTimeAsLocalDateTime();
        if (startTime != null
            && recordingSessionRepository.findByRecordingId(recordingId).isEmpty()) {
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
      } else {
        clearTaskState();
      }
    } else {
      // Plan auto-record disabled; clear all task state so plan context does not leak.
      clearTaskState();
    }
  }

  /**
   * Shared stop/finalize helper for task-driven recordings.
   *
   * <p>Calls {@link RecordingService#stopRecording()}, persists the returned {@link
   * RecordingMetadata} into the matching {@link RecordingSession} row (updating it if it exists, or
   * creating a fallback row if the start-time insert was skipped), and clears the current task
   * context so stale state does not leak into the next recording.
   *
   * <p>If {@code stopRecording()} returns {@code null}, the finalization is treated as a failure:
   * an error is logged and no bogus DB row is written. If {@code stopRecording()} throws, the
   * exception is logged and swallowed so it does not break the script lifecycle (e.g. the {@code
   * ScriptInstance} finally chain that broadcasts a stopped state). In all cases task state is
   * cleared via the single {@code finally} block.
   *
   * @param reason human-readable description of why finalization is happening, for logging only
   */
  private void finalizeCurrentRecording(String reason) {
    RecordingMetadata metadata = null;
    try {
      metadata = recordingService.stopRecording();
      if (metadata == null) {
        log.error(
            "Failed to finalize recording for {}: stopRecording() returned null; metadata not persisted",
            reason);
        return;
      }

      Optional<RecordingSession> existingOpt =
          recordingSessionRepository.findByRecordingId(metadata.getId());
      RecordingSession session;
      if (existingOpt.isPresent()) {
        session = existingOpt.get();
        log.info(
            "Updating existing RecordingSession {} with finalized metadata for {}",
            metadata.getId(),
            reason);
      } else {
        // Start-time row creation was skipped; create a fallback row so the recording
        // metadata is not lost and the replay page can still resolve the video.
        log.warn(
            "RecordingSession not found for {}; creating fallback row for {}",
            metadata.getId(),
            reason);
        session = createFallbackSession(metadata);
      }

      session.setEndTime(LocalDateTime.now());
      session.setVideoPath(metadata.getFilePath());
      session.setDurationMs(metadata.getDuration());
      session.setFrameCount(metadata.getFrameCount());
      session.setFps(metadata.getFps());
      session.setVideoCodec(metadata.getVideoCodec());
      // replayName and replayTagsJson are preserved on existing rows because they are
      // only set here if they were already present; new fallback rows leave them null.

      RecordingSession saved = recordingSessionRepository.save(session);

      // Soft validation (background stop path must not throw to a waiting client unlike the
      // manual REST path): warn if the video file is missing or the save did not persist.
      if (metadata.getFilePath() != null && !new File(metadata.getFilePath()).exists()) {
        log.warn(
            "Finalized video file not found on disk for {}: {}",
            metadata.getId(),
            metadata.getFilePath());
      }
      if (saved != null && saved.getVideoPath() == null) {
        log.error("Failed to persist videoPath for recording {}", metadata.getId());
      }

      log.info(
          "Finalized recording {} for {} - video: {}, duration: {}ms, frames: {}, fps: {}, codec: {}",
          metadata.getId(),
          reason,
          metadata.getFilePath(),
          metadata.getDuration(),
          metadata.getFrameCount(),
          metadata.getFps(),
          metadata.getVideoCodec());
    } catch (Exception e) {
      log.error(
          "Failed to finalize recording for {} (recordingId={}): {}",
          reason,
          metadata != null ? metadata.getId() : "unknown",
          e.getMessage(),
          e);
    } finally {
      // Always clear task state, even if stopRecording() threw, so stale context never
      // leaks into the next recording.
      clearTaskState();
    }
  }

  /**
   * Build a fallback {@link RecordingSession} row from metadata when the start-time insert was
   * skipped.
   *
   * <p>Identity fields are derived from the current task context:
   *
   * <ul>
   *   <li>plan-linked task: accountName = {@code "plan-task"}, scriptName = currentTaskPlanId
   *   <li>normal detected task: accountName = {@code "auto-detected"}, scriptName =
   *       currentTask.getDisplayName()
   * </ul>
   */
  private RecordingSession createFallbackSession(RecordingMetadata metadata) {
    String accountName;
    String scriptName;
    if (currentTaskPlanId != null) {
      accountName = "plan-task";
      scriptName = currentTaskPlanId;
    } else {
      accountName = "auto-detected";
      scriptName = currentTask != null ? currentTask.getDisplayName() : "unknown";
    }

    LocalDateTime startTime =
        LocalDateTime.ofInstant(
            Instant.ofEpochMilli(metadata.getStartTime()), ZoneId.systemDefault());

    RecordingSession session =
        new RecordingSession(
            metadata.getId(),
            accountName,
            scriptName,
            startTime,
            RecordingSession.SessionTrigger.AUTO_START,
            null);
    return session;
  }

  /** Clear current task context so stale state does not leak into the next recording. */
  private void clearTaskState() {
    currentTask = null;
    currentTaskPlanId = null;
    currentTaskItemId = null;
  }
}
