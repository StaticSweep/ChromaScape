package com.chromascape.web.recording.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.recording.config.RecordingTriggerSource;
import com.chromascape.web.recording.config.TaskType;
import com.chromascape.web.recording.entity.RecordingSession;
import com.chromascape.web.recording.entity.RecordingSessionRepository;
import com.chromascape.web.recording.service.RecordingService.RecordingMetadata;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Focused unit tests for {@link TaskDetectionService} task auto-record start/stop/finalize
 * behavior.
 *
 * <p>These tests verify that task-driven recordings persist the same metadata fields (videoPath,
 * endTime, durationMs, frameCount, fps, videoCodec) that manual/debug-session recordings already
 * persist, covering normal completion, task-switch, fallback row creation, null-metadata failure,
 * stopRecording throw handling, failed-start state, and stale plan-context leak prevention.
 */
@ExtendWith(MockitoExtension.class)
class TaskDetectionServiceTest {

  @Mock private RecordingService recordingService;
  @Mock private TaskRecordingPreferenceService taskPreferenceService;
  @Mock private RecordingSessionRepository recordingSessionRepository;

  private TaskDetectionService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskDetectionService(
            recordingService, taskPreferenceService, recordingSessionRepository);
  }

  private RecordingMetadata metadata(
      String id, String path, long startMs, long duration, long frames, int fps, String codec) {
    return new RecordingMetadata(id, path, startMs, duration, frames, fps, 0L, codec);
  }

  /**
   * onTaskEnded() with an existing RecordingSession row: stopRecording() returns metadata and the
   * repository row is updated with videoPath, endTime, durationMs, frameCount, fps, videoCodec.
   */
  @Test
  void onTaskEnded_existingRow_updatesMetadata() {
    RecordingSession existing =
        new RecordingSession(
            "rec-1",
            "auto-detected",
            "Woodcutting",
            LocalDateTime.of(2025, 6, 15, 12, 0),
            RecordingSession.SessionTrigger.AUTO_START,
            null);
    when(taskPreferenceService.isTaskEnabled(TaskType.WOODCUTTING)).thenReturn(true);
    when(recordingService.startRecordingAuto("Woodcutting", RecordingTriggerSource.TASK_AUTO))
        .thenReturn("rec-1");
    when(recordingService.getRecordingStartTimeAsLocalDateTime())
        .thenReturn(LocalDateTime.of(2025, 6, 15, 12, 0));
    when(recordingSessionRepository.findByRecordingId("rec-1")).thenReturn(Optional.of(existing));

    service.onTaskDetected(TaskType.WOODCUTTING, "wc-script");

    when(recordingService.isRecording()).thenReturn(true);
    RecordingMetadata md =
        metadata("rec-1", "/videos/rec-1.mp4", 1718450000000L, 5000L, 150L, 30, "libx264");
    when(recordingService.stopRecording()).thenReturn(md);
    when(recordingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.onTaskEnded();

    verify(recordingSessionRepository)
        .save(
            argThat(
                s ->
                    "rec-1".equals(s.getRecordingId())
                        && "/videos/rec-1.mp4".equals(s.getVideoPath())
                        && s.getEndTime() != null
                        && Long.valueOf(5000L).equals(s.getDurationMs())
                        && Long.valueOf(150L).equals(s.getFrameCount())
                        && Integer.valueOf(30).equals(s.getFps())
                        && "libx264".equals(s.getVideoCodec())
                        && s.getTriggerType() == RecordingSession.SessionTrigger.AUTO_START));
    assertNull(service.getCurrentTask());
  }

  /**
   * onTaskDetected() when switching tasks while recording: the previous recording is finalized
   * (metadata persisted) before the new recording starts, and the old row gets the persisted
   * metadata.
   */
  @Test
  void onTaskDetected_switchingTasks_finalizesPreviousRecording() {
    RecordingSession existingRec1 =
        new RecordingSession(
            "rec-1",
            "auto-detected",
            "Woodcutting",
            LocalDateTime.of(2025, 6, 15, 12, 0),
            RecordingSession.SessionTrigger.AUTO_START,
            null);
    when(recordingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    when(taskPreferenceService.isTaskEnabled(TaskType.WOODCUTTING)).thenReturn(true);
    when(recordingService.startRecordingAuto("Woodcutting", RecordingTriggerSource.TASK_AUTO))
        .thenReturn("rec-1");
    when(recordingService.getRecordingStartTimeAsLocalDateTime())
        .thenReturn(LocalDateTime.of(2025, 6, 15, 12, 0));
    when(recordingSessionRepository.findByRecordingId("rec-1"))
        .thenReturn(Optional.of(existingRec1));
    service.onTaskDetected(TaskType.WOODCUTTING, "wc-script");

    when(recordingService.isRecording()).thenReturn(true);
    RecordingMetadata md =
        metadata("rec-1", "/videos/rec-1.mp4", 1718450000000L, 5000L, 150L, 30, "libx264");
    when(recordingService.stopRecording()).thenReturn(md);
    when(taskPreferenceService.isTaskEnabled(TaskType.MINING)).thenReturn(true);
    when(recordingService.startRecordingAuto("Mining", RecordingTriggerSource.TASK_AUTO))
        .thenReturn("rec-2");
    when(recordingSessionRepository.findByRecordingId("rec-2")).thenReturn(Optional.empty());

    service.onTaskDetected(TaskType.MINING, "mining-script");

    verify(recordingService).stopRecording();
    verify(recordingSessionRepository)
        .save(
            argThat(
                s ->
                    "rec-1".equals(s.getRecordingId())
                        && "/videos/rec-1.mp4".equals(s.getVideoPath())
                        && Long.valueOf(5000L).equals(s.getDurationMs())
                        && "libx264".equals(s.getVideoCodec())));
    assertEquals(TaskType.MINING, service.getCurrentTask());
  }

  /**
   * onTaskEnded() when the row does not already exist: a fallback RecordingSession row is created
   * and saved with the metadata (e.g. when start-time row creation was skipped because the start
   * time was unavailable).
   */
  @Test
  void onTaskEnded_missingRow_createsFallbackRow() {
    when(taskPreferenceService.isTaskEnabled(TaskType.WOODCUTTING)).thenReturn(true);
    when(recordingService.startRecordingAuto("Woodcutting", RecordingTriggerSource.TASK_AUTO))
        .thenReturn("rec-1");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(null);

    service.onTaskDetected(TaskType.WOODCUTTING, "wc-script");

    when(recordingService.isRecording()).thenReturn(true);
    RecordingMetadata md =
        metadata("rec-1", "/videos/rec-1.mp4", 1718450000000L, 5000L, 150L, 30, "libx264");
    when(recordingService.stopRecording()).thenReturn(md);
    when(recordingSessionRepository.findByRecordingId("rec-1")).thenReturn(Optional.empty());
    when(recordingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.onTaskEnded();

    verify(recordingSessionRepository)
        .save(
            argThat(
                s ->
                    "rec-1".equals(s.getRecordingId())
                        && "auto-detected".equals(s.getAccountName())
                        && "Woodcutting".equals(s.getScriptName())
                        && "/videos/rec-1.mp4".equals(s.getVideoPath())
                        && s.getEndTime() != null
                        && s.getStartTime() != null
                        && Long.valueOf(5000L).equals(s.getDurationMs())
                        && Long.valueOf(150L).equals(s.getFrameCount())
                        && Integer.valueOf(30).equals(s.getFps())
                        && "libx264".equals(s.getVideoCodec())
                        && s.getTriggerType() == RecordingSession.SessionTrigger.AUTO_START));
    assertNull(service.getCurrentTask());
  }

  /**
   * onTaskEnded() when stopRecording() returns null: no bogus DB finalization occurs, the service
   * clears task state, and the failure is treated as finalization failure (no videoPath persisted).
   */
  @Test
  void onTaskEnded_stopRecordingReturnsNull_clearsStateWithoutBogusFinalization() {
    when(taskPreferenceService.isTaskEnabled(TaskType.WOODCUTTING)).thenReturn(true);
    when(recordingService.startRecordingAuto("Woodcutting", RecordingTriggerSource.TASK_AUTO))
        .thenReturn("rec-1");
    when(recordingService.getRecordingStartTimeAsLocalDateTime())
        .thenReturn(LocalDateTime.of(2025, 6, 15, 12, 0));
    when(recordingSessionRepository.findByRecordingId("rec-1")).thenReturn(Optional.empty());
    when(recordingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.onTaskDetected(TaskType.WOODCUTTING, "wc-script");

    when(recordingService.isRecording()).thenReturn(true);
    when(recordingService.stopRecording()).thenReturn(null);

    service.onTaskEnded();

    verify(recordingSessionRepository, never()).save(argThat(s -> s.getVideoPath() != null));
    assertNull(service.getCurrentTask());
    assertNull(service.getCurrentTaskPlanId());
    assertNull(service.getCurrentTaskItemId());
  }

  /**
   * onTaskEnded() when stopRecording() throws: the exception is swallowed (so the script lifecycle
   * is not broken), no bogus DB finalization occurs, and task state is still cleared via the
   * finally block.
   */
  @Test
  void onTaskEnded_stopRecordingThrows_clearsStateAndDoesNotPropagate() {
    when(taskPreferenceService.isTaskEnabled(TaskType.WOODCUTTING)).thenReturn(true);
    when(recordingService.startRecordingAuto("Woodcutting", RecordingTriggerSource.TASK_AUTO))
        .thenReturn("rec-1");
    when(recordingService.getRecordingStartTimeAsLocalDateTime())
        .thenReturn(LocalDateTime.of(2025, 6, 15, 12, 0));
    when(recordingSessionRepository.findByRecordingId("rec-1")).thenReturn(Optional.empty());
    when(recordingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.onTaskDetected(TaskType.WOODCUTTING, "wc-script");

    when(recordingService.isRecording()).thenReturn(true);
    when(recordingService.stopRecording()).thenThrow(new RuntimeException("encoder flush failed"));

    // Must not propagate.
    assertDoesNotThrow(() -> service.onTaskEnded());

    // No row with a videoPath should be saved after a throw.
    verify(recordingSessionRepository, never()).save(argThat(s -> s.getVideoPath() != null));
    // State must still be cleared.
    assertNull(service.getCurrentTask());
    assertNull(service.getCurrentTaskPlanId());
    assertNull(service.getCurrentTaskItemId());
  }

  /**
   * onTaskDetected() when startRecordingAuto returns null: the task is NOT marked active, so a
   * later detection of the same task can retry the start instead of being short-circuited by the
   * early-return guard.
   */
  @Test
  void onTaskDetected_startReturnsNull_doesNotMarkTaskActive() {
    when(taskPreferenceService.isTaskEnabled(TaskType.WOODCUTTING)).thenReturn(true);
    when(recordingService.startRecordingAuto("Woodcutting", RecordingTriggerSource.TASK_AUTO))
        .thenReturn(null);

    service.onTaskDetected(TaskType.WOODCUTTING, "wc-script");

    assertNull(service.getCurrentTask());
    assertNull(service.getCurrentTaskPlanId());
    assertNull(service.getCurrentTaskItemId());
  }

  /**
   * Regression: task auto-record start behavior when the task is not enabled does not start a
   * recording and clears all task state (so stale plan context cannot leak).
   */
  @Test
  void onTaskDetected_taskNotEnabled_doesNotStartRecordingAndClearsState() {
    when(taskPreferenceService.isTaskEnabled(TaskType.FISHING)).thenReturn(false);

    service.onTaskDetected(TaskType.FISHING, "fishing-script");

    verify(recordingService, never()).startRecordingAuto(anyString(), any());
    assertNull(service.getCurrentTask());
    assertNull(service.getCurrentTaskPlanId());
    assertNull(service.getCurrentTaskItemId());
  }

  /**
   * Regression: onTaskEnded() with no active recording simply clears state without touching the
   * recorder or repository.
   */
  @Test
  void onTaskEnded_noActiveRecording_clearsStateOnly() {
    service.onTaskEnded();

    verify(recordingService, never()).stopRecording();
    verify(recordingSessionRepository, never()).save(any());
    assertNull(service.getCurrentTask());
  }

  /**
   * onPlanTaskDetected() when plan auto-record is disabled: no recording is started and ALL task
   * state (including plan context) is cleared so it cannot leak into a later recording.
   */
  @Test
  void onPlanTaskDetected_planDisabled_clearsAllTaskState() {
    when(taskPreferenceService.isTaskEnabled(TaskType.OTHER)).thenReturn(false);

    service.onPlanTaskDetected("plan-1", "item-1", "plan-script");

    verify(recordingService, never()).startRecordingAuto(anyString(), any());
    assertNull(service.getCurrentTask());
    assertNull(service.getCurrentTaskPlanId());
    assertNull(service.getCurrentTaskItemId());
  }

  /**
   * onPlanTaskDetected() when startRecordingAuto returns null: plan context is NOT stored, so a
   * failed plan start does not later mis-classify a normal-task fallback row as plan-linked.
   */
  @Test
  void onPlanTaskDetected_startReturnsNull_doesNotStorePlanContext() {
    when(taskPreferenceService.isTaskEnabled(TaskType.OTHER)).thenReturn(true);
    when(recordingService.startRecordingAuto("plan-task", RecordingTriggerSource.TASK_AUTO))
        .thenReturn(null);

    service.onPlanTaskDetected("plan-1", "item-1", "plan-script");

    assertNull(service.getCurrentTask());
    assertNull(service.getCurrentTaskPlanId());
    assertNull(service.getCurrentTaskItemId());
  }

  /**
   * Stale plan context must not leak into a later normal-task fallback row. Sequence: plan path is
   * disabled (clears state) -> a normal task starts but the start-time insert is skipped (start
   * time null) -> on task end a fallback row is created and must be classified as auto-detected,
   * NOT plan-task.
   */
  @Test
  void onTaskDetected_afterDisabledPlan_doesNotLeakPlanIntoFallbackRow() {
    // Step 1: plan path disabled -> state cleared, no leak.
    when(taskPreferenceService.isTaskEnabled(TaskType.OTHER)).thenReturn(false);
    service.onPlanTaskDetected("plan-1", "item-1", "plan-script");
    assertNull(service.getCurrentTaskPlanId());

    // Step 2: normal task starts, but start-time row creation is skipped (start time null).
    when(taskPreferenceService.isTaskEnabled(TaskType.WOODCUTTING)).thenReturn(true);
    when(recordingService.startRecordingAuto("Woodcutting", RecordingTriggerSource.TASK_AUTO))
        .thenReturn("rec-1");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(null);
    service.onTaskDetected(TaskType.WOODCUTTING, "wc-script");

    // Step 3: end -> fallback row created; must be auto-detected, not plan-task.
    when(recordingService.isRecording()).thenReturn(true);
    RecordingMetadata md =
        metadata("rec-1", "/videos/rec-1.mp4", 1718450000000L, 5000L, 150L, 30, "libx264");
    when(recordingService.stopRecording()).thenReturn(md);
    when(recordingSessionRepository.findByRecordingId("rec-1")).thenReturn(Optional.empty());
    when(recordingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.onTaskEnded();

    verify(recordingSessionRepository)
        .save(
            argThat(
                s ->
                    "auto-detected".equals(s.getAccountName())
                        && "Woodcutting".equals(s.getScriptName())
                        && !"plan-task".equals(s.getAccountName())));
  }

  /**
   * onPlanTaskDetected() with a successful start creates a plan-task session row, and finalization
   * updates that row (or creates a plan-task fallback row if the start-time insert was skipped).
   */
  @Test
  void onPlanTaskDetected_createsAndFinalizesPlanRecording() {
    // Plan start succeeds but start-time row creation is skipped (start time null) so the
    // fallback path on finalize must create a plan-task row.
    when(taskPreferenceService.isTaskEnabled(TaskType.OTHER)).thenReturn(true);
    when(recordingService.startRecordingAuto("plan-task", RecordingTriggerSource.TASK_AUTO))
        .thenReturn("rec-plan");
    when(recordingService.getRecordingStartTimeAsLocalDateTime()).thenReturn(null);

    service.onPlanTaskDetected("plan-42", "item-7", "plan-script");
    assertEquals("plan-42", service.getCurrentTaskPlanId());
    assertEquals(TaskType.OTHER, service.getCurrentTask());

    // Finalize with a missing row -> plan-task fallback row.
    when(recordingService.isRecording()).thenReturn(true);
    RecordingMetadata md =
        metadata("rec-plan", "/videos/rec-plan.mp4", 1718450000000L, 8000L, 240L, 30, "libx264");
    when(recordingService.stopRecording()).thenReturn(md);
    when(recordingSessionRepository.findByRecordingId("rec-plan")).thenReturn(Optional.empty());
    when(recordingSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.onTaskEnded();

    verify(recordingSessionRepository)
        .save(
            argThat(
                s ->
                    "rec-plan".equals(s.getRecordingId())
                        && "plan-task".equals(s.getAccountName())
                        && "plan-42".equals(s.getScriptName())
                        && "/videos/rec-plan.mp4".equals(s.getVideoPath())
                        && Long.valueOf(8000L).equals(s.getDurationMs())
                        && s.getTriggerType() == RecordingSession.SessionTrigger.AUTO_START));
    assertNull(service.getCurrentTask());
    assertNull(service.getCurrentTaskPlanId());
  }
}
