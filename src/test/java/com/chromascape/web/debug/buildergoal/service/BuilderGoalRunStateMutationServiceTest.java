package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.entity.PatchLoopFailureCode;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.patchloop.service.PatchLoopPersistenceService;
import com.chromascape.web.debug.patchloop.service.ScriptExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BuilderGoalRunStateMutationServiceTest {

  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private PatchLoopPersistenceService persistenceService;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private BuilderGoalRunStateMutationService mutationService;

  @BeforeEach
  void setUp() {
    mutationService =
        new BuilderGoalRunStateMutationService(
            iterationRepository, persistenceService, objectMapper);
  }

  private PatchLoopIteration newIteration(String iterationId) {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId(iterationId);
    iteration.setScriptName("FISHING_TRAINING");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(new LinkedHashMap<String, Object>()));
    return iteration;
  }

  private PatchLoopIteration terminalIteration(String iterationId) {
    PatchLoopIteration iteration = newIteration(iterationId);
    iteration.setStatus(PatchLoopStatus.FAILED);
    return iteration;
  }

  private PatchLoopIteration cancelledIteration(String iterationId) {
    PatchLoopIteration iteration = newIteration(iterationId);
    iteration.setStatus(PatchLoopStatus.CANCELLED);
    return iteration;
  }

  private PatchLoopIteration publishedIteration(String iterationId, String publishStatus) {
    PatchLoopIteration iteration = newIteration(iterationId);
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", publishStatus);
    publish.put("published_at", LocalDateTime.now().minusSeconds(1).toString());
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    return iteration;
  }

  private ScriptExecutionService.Micro6RunStatusResult executorStatus(
      String status, String errorMessage, Map<String, Object> rawResponse) {
    return new ScriptExecutionService.Micro6RunStatusResult(
        true,
        status,
        errorMessage,
        objectMapper.valueToTree(rawResponse),
        "http://127.0.0.1:8098",
        1001);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> readPublish(PatchLoopIteration iteration) {
    Map<String, Object> metadata =
        objectMapper.convertValue(iteration.getTaskMetadataJson(), Map.class);
    return (Map<String, Object>) metadata.get("micro6_publish");
  }

  private Map<String, Object> publishMetadata(Map<String, Object> taskMetadata) {
    @SuppressWarnings("unchecked")
    Map<String, Object> publish = (Map<String, Object>) taskMetadata.get("micro6_publish");
    if (publish == null) {
      publish = new LinkedHashMap<>();
      taskMetadata.put("micro6_publish", publish);
    }
    return publish;
  }

  // --- reservePublish tests ---

  @Test
  void reservePublish_persistsEntryIdAndPublishingStatus() {
    PatchLoopIteration iteration = newIteration("bg_test0001");
    when(iterationRepository.findByIterationIdForUpdate("bg_test0001"))
        .thenReturn(Optional.of(iteration));

    BuilderGoalRunStateMutationService.PublishReservation reservation =
        mutationService.reservePublish("bg_test0001", 1001, LocalDateTime.now());

    assertThat(reservation.iterationId()).isEqualTo("bg_test0001");
    assertThat(reservation.statusBeforeReserve()).isEqualTo(PatchLoopStatus.QUEUED);
    assertThat(reservation.taskMetadataSnapshot()).isNotNull();
    assertThat(reservation.publishMetadataSnapshot()).containsEntry("entry_id", 1001);
    assertThat(reservation.publishMetadataSnapshot()).containsEntry("status", "PUBLISHING");
  }

  @Test
  void reservePublish_rejectsTerminalRun() {
    PatchLoopIteration iteration = terminalIteration("bg_term0001");
    when(iterationRepository.findByIterationIdForUpdate("bg_term0001"))
        .thenReturn(Optional.of(iteration));

    assertThatThrownBy(
            () -> mutationService.reservePublish("bg_term0001", 1001, LocalDateTime.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal");
  }

  @Test
  void reservePublish_rejectsStoppedRun() {
    PatchLoopIteration iteration = newIteration("bg_stopped0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("stop_requested_at", LocalDateTime.now().toString());
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_stopped0001"))
        .thenReturn(Optional.of(iteration));

    assertThatThrownBy(
            () -> mutationService.reservePublish("bg_stopped0001", 1001, LocalDateTime.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stopped");
  }

  @Test
  void reservePublish_rejectsAlreadyReservedRun() {
    PatchLoopIteration iteration = newIteration("bg_already0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 42);
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_already0001"))
        .thenReturn(Optional.of(iteration));

    assertThatThrownBy(
            () -> mutationService.reservePublish("bg_already0001", 1001, LocalDateTime.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already reserved");
  }

  // --- applyPublishOutcome tests ---

  @Test
  void applyPublishOutcome_mergesPublishFieldsAndReturnsResult() {
    PatchLoopIteration iteration = newIteration("bg_outcome0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISHING");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_outcome0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6PublishResult result =
        ScriptExecutionService.Micro6PublishResult.success(
            1001, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null);

    BuilderGoalRunStateMutationService.ApplyOutcomeResult outcome =
        mutationService.applyPublishOutcome("bg_outcome0001", 1001, result);

    assertThat(outcome.entryId()).isEqualTo(1001);
    assertThat(outcome.stopRequested()).isFalse();
    assertThat(outcome.needsExecutorCancel()).isFalse();
    assertThat(outcome.mergedPublishMetadata()).containsEntry("status", "QUEUED");
    assertThat(outcome.mergedPublishMetadata())
        .containsEntry("micro6_base_url", "http://127.0.0.1:8098");
  }

  @Test
  void applyPublishOutcome_rejectsEntryIdMismatch() {
    PatchLoopIteration iteration = newIteration("bg_mismatch0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 999);
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_mismatch0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6PublishResult result =
        ScriptExecutionService.Micro6PublishResult.success(
            1001, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null);

    assertThatThrownBy(() -> mutationService.applyPublishOutcome("bg_mismatch0001", 1001, result))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Entry id mismatch");
  }

  @Test
  void applyPublishOutcome_stopWinsOverPublishedStatus() {
    PatchLoopIteration iteration = newIteration("bg_stopwins0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("stop_requested_at", LocalDateTime.now().toString());
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISHING");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_stopwins0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6PublishResult result =
        ScriptExecutionService.Micro6PublishResult.success(
            1001, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null);

    BuilderGoalRunStateMutationService.ApplyOutcomeResult outcome =
        mutationService.applyPublishOutcome("bg_stopwins0001", 1001, result);

    assertThat(outcome.stopRequested()).isTrue();
    assertThat(outcome.needsExecutorCancel()).isTrue();
    assertThat(outcome.mergedPublishMetadata()).containsEntry("status", "STOPPING");
  }

  @Test
  void applyPublishOutcome_ignoresTerminalRun() {
    PatchLoopIteration iteration = terminalIteration("bg_term_outcome0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_term_outcome0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6PublishResult result =
        ScriptExecutionService.Micro6PublishResult.success(
            1001, true, "http://127.0.0.1:8098", "QUEUED", "QUEUED", null);

    BuilderGoalRunStateMutationService.ApplyOutcomeResult outcome =
        mutationService.applyPublishOutcome("bg_term_outcome0001", 1001, result);

    assertThat(outcome.stopRequested()).isFalse();
    assertThat(outcome.needsExecutorCancel()).isFalse();
  }

  @Test
  void applyPublishOutcome_reconcilingStatusPreservesRunTimestamps() {
    PatchLoopIteration iteration = newIteration("bg_reconciling0001");
    iteration.setRunEndTime(LocalDateTime.now().minusMinutes(1));
    iteration.setRunSuccess(false);
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISHING");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_reconciling0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6PublishResult reconciling =
        new ScriptExecutionService.Micro6PublishResult(
            false,
            "micro6_local_executor",
            "RECONCILING",
            null,
            null,
            1001,
            false,
            "http://127.0.0.1:8098",
            "Awaiting executor confirmation for entry 1001");

    BuilderGoalRunStateMutationService.ApplyOutcomeResult outcome =
        mutationService.applyPublishOutcome("bg_reconciling0001", 1001, reconciling);

    assertThat(outcome.stopRequested()).isFalse();
    assertThat(outcome.mergedPublishMetadata()).containsEntry("status", "RECONCILING");
  }

  @Test
  void applyExecutorStatus_persistsCompletedPublishStatusBeforeTerminalTransition() {
    PatchLoopIteration iteration = publishedIteration("bg_status_completed0001", "PUBLISHING");
    when(iterationRepository.findByIterationIdForUpdate("bg_status_completed0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult statusResult =
        executorStatus(
            "COMPLETED",
            null,
            Map.of("status", "COMPLETED", "task_started", true, "debug_session_id", "debug-1"));

    BuilderGoalRunStateMutationService.StatusApplyResult result =
        mutationService.applyExecutorStatus(
            "bg_status_completed0001", 1001, statusResult, LocalDateTime.now());

    assertThat(result.terminal()).isTrue();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.COMPLETED);
    assertThat(readPublish(iteration)).containsEntry("status", "COMPLETED");
    assertThat(iteration.getRunSuccess()).isTrue();
  }

  @Test
  void applyExecutorStatus_persistsFailedPublishStatusBeforeTerminalTransition() {
    PatchLoopIteration iteration = publishedIteration("bg_status_failed0001", "RUNNING");
    when(iterationRepository.findByIterationIdForUpdate("bg_status_failed0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult statusResult =
        executorStatus("FAILED", "Fish task failed", Map.of("status", "FAILED"));

    BuilderGoalRunStateMutationService.StatusApplyResult result =
        mutationService.applyExecutorStatus(
            "bg_status_failed0001", 1001, statusResult, LocalDateTime.now());

    assertThat(result.terminal()).isTrue();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.FAILED);
    assertThat(readPublish(iteration)).containsEntry("status", "FAILED");
    assertThat(readPublish(iteration)).containsEntry("last_error", "Fish task failed");
    assertThat(iteration.getRunSuccess()).isFalse();
  }

  @Test
  void applyExecutorStatus_persistsCancelledPublishStatusBeforeTerminalTransition() {
    PatchLoopIteration iteration = publishedIteration("bg_status_cancelled0001", "STOPPING");
    when(iterationRepository.findByIterationIdForUpdate("bg_status_cancelled0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult statusResult =
        executorStatus("CANCELLED", null, Map.of("status", "CANCELLED"));

    BuilderGoalRunStateMutationService.StatusApplyResult result =
        mutationService.applyExecutorStatus(
            "bg_status_cancelled0001", 1001, statusResult, LocalDateTime.now());

    assertThat(result.terminal()).isTrue();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.CANCELLED);
    assertThat(readPublish(iteration)).containsEntry("status", "CANCELLED");
    assertThat(iteration.getRunSuccess()).isNull();
  }

  @Test
  void applyExecutorStatus_mapsAcceptedQueuedAssignedRunning() {
    PatchLoopIteration iteration = publishedIteration("bg_activation0001", "ACCEPTED");
    when(iterationRepository.findByIterationIdForUpdate("bg_activation0001"))
        .thenReturn(Optional.of(iteration));

    // ACCEPTED persists as ACCEPTED.
    mutationService.applyExecutorStatus(
        "bg_activation0001",
        1001,
        executorStatus(
            "ACCEPTED", null, Map.of("status", "ACCEPTED", "activation_state", "RECEIVED")),
        LocalDateTime.now());
    assertThat(readPublish(iteration)).containsEntry("status", "ACCEPTED");
    assertThat(readPublish(iteration)).containsEntry("activation_state", "RECEIVED");

    // QUEUED persists as QUEUED.
    mutationService.applyExecutorStatus(
        "bg_activation0001",
        1001,
        executorStatus("QUEUED", null, Map.of("status", "QUEUED", "activation_state", "QUEUED")),
        LocalDateTime.now());
    assertThat(readPublish(iteration)).containsEntry("status", "QUEUED");

    // ASSIGNED persists as ASSIGNED.
    mutationService.applyExecutorStatus(
        "bg_activation0001",
        1001,
        executorStatus(
            "ASSIGNED", null, Map.of("status", "ASSIGNED", "activation_state", "ASSIGNED")),
        LocalDateTime.now());
    assertThat(readPublish(iteration)).containsEntry("status", "ASSIGNED");

    // RUNNING with a confirmed task start transitions the row to RUNNING_SCRIPT.
    mutationService.applyExecutorStatus(
        "bg_activation0001",
        1001,
        executorStatus(
            "RUNNING",
            null,
            Map.of(
                "status", "RUNNING",
                "activation_state", "ACTIVE",
                "task_started", true,
                "debug_session_id", "session-1")),
        LocalDateTime.now());
    assertThat(readPublish(iteration)).containsEntry("status", "RUNNING");
    assertThat(readPublish(iteration)).containsEntry("activation_state", "ACTIVE");
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.RUNNING_SCRIPT);
  }

  @Test
  void applyExecutorStatus_pausedSurvivesReconciliationAsDistinctStatus() {
    PatchLoopIteration iteration = publishedIteration("bg_paused0001", "ACCEPTED");
    when(iterationRepository.findByIterationIdForUpdate("bg_paused0001"))
        .thenReturn(Optional.of(iteration));

    mutationService.applyExecutorStatus(
        "bg_paused0001",
        1001,
        executorStatus(
            "PAUSED",
            null,
            Map.of(
                "status", "PAUSED",
                "activation_state", "PAUSED",
                "task_started", true,
                "debug_session_id", "session-1")),
        LocalDateTime.now());

    assertThat(readPublish(iteration)).containsEntry("status", "PAUSED");
    assertThat(readPublish(iteration)).containsEntry("activation_state", "PAUSED");
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.RUNNING_SCRIPT);
  }

  @Test
  void applyExecutorStatus_pausedRemainsStoppableAndNotTerminal() {
    PatchLoopIteration iteration = publishedIteration("bg_paused_stop0001", "PAUSED");
    when(iterationRepository.findByIterationIdForUpdate("bg_paused_stop0001"))
        .thenReturn(Optional.of(iteration));

    mutationService.applyExecutorStatus(
        "bg_paused_stop0001",
        1001,
        executorStatus(
            "PAUSED",
            null,
            Map.of(
                "status", "PAUSED",
                "activation_state", "PAUSED",
                "task_started", true,
                "debug_session_id", "session-1")),
        LocalDateTime.now());

    // PAUSED stays active/non-terminal: the row must not be terminal and must remain eligible for
    // stop/resume reconciliation.
    assertThat(readPublish(iteration)).containsEntry("status", "PAUSED");
    assertThat(iteration.getStatus().isTerminal()).isFalse();
  }

  @Test
  void applyExecutorStatus_pausedWithoutStartBindingIsStillActivatedAndActive() {
    PatchLoopIteration iteration = publishedIteration("bg_paused_unbound0001", "QUEUED");
    when(iterationRepository.findByIterationIdForUpdate("bg_paused_unbound0001"))
        .thenReturn(Optional.of(iteration));

    // A pause can race the task-start/debug-session callback.  PAUSED itself is the executor's
    // authoritative activation signal; missing optional binding fields must not trigger the start
    // timeout or turn the run into a waiting/failed state.
    BuilderGoalRunStateMutationService.StatusApplyResult result =
        mutationService.applyExecutorStatus(
            "bg_paused_unbound0001",
            1001,
            executorStatus(
                "PAUSED", null, Map.of("status", "PAUSED", "activation_state", "PAUSED")),
            LocalDateTime.now().plusMinutes(10));

    assertThat(result.terminal()).isFalse();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.RUNNING_SCRIPT);
    assertThat(readPublish(iteration)).containsEntry("status", "PAUSED");
    assertThat(readPublish(iteration)).containsEntry("activation_state", "PAUSED");
    @SuppressWarnings("unchecked")
    Map<String, Object> metadata =
        objectMapper.convertValue(iteration.getTaskMetadataJson(), Map.class);
    assertThat(metadata).containsEntry("task_start_confirmed", true);
  }

  @Test
  void applyExecutorStatus_queuedWithStoppedScriptTimesOutWithScriptStartFailed() {
    PatchLoopIteration iteration = newIteration("bg_start_timeout0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "QUEUED");
    // Older than the 300s run-timeout measured from published_at.
    publish.put("published_at", LocalDateTime.now().minusSeconds(301).toString());
    publish.put("activation_state", "QUEUED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_start_timeout0001"))
        .thenReturn(Optional.of(iteration));

    BuilderGoalRunStateMutationService.StatusApplyResult result =
        mutationService.applyExecutorStatus(
            "bg_start_timeout0001",
            1001,
            executorStatus(
                "QUEUED", null, Map.of("status", "QUEUED", "activation_state", "QUEUED")),
            LocalDateTime.now());

    assertThat(result.terminal()).isTrue();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.FAILED);
    assertThat(iteration.getFailureCode()).isEqualTo(PatchLoopFailureCode.SCRIPT_START_FAILED);
    assertThat(iteration.getFailureReason()).contains("Task start timed out").contains("QUEUED");
  }

  @Test
  void failStaleLaunchIfActive_failsCurrentRowAtomically() {
    PatchLoopIteration iteration = newIteration("bg_stale_exit0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(
        "target_preparation",
        Map.of(
            "state", "PROCESS_EXITED",
            "message", "Micro6 process exited",
            "readiness_reason", "Process exited before readiness"));
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_stale_exit0001"))
        .thenReturn(Optional.of(iteration));

    boolean failed = mutationService.failStaleLaunchIfActive("bg_stale_exit0001");

    assertThat(failed).isTrue();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.FAILED);
    assertThat(iteration.getRunSuccess()).isFalse();
    assertThat(iteration.getFailureReason()).isEqualTo("Micro6 process exited");
  }

  @Test
  void failStaleLaunchIfActive_doesNotOverwriteConcurrentStopIntent() {
    PatchLoopIteration iteration = newIteration("bg_stale_stop0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("stop_requested_at", LocalDateTime.now().toString());
    metadata.put("stop_reason", "Operator stopped launch");
    metadata.put("target_preparation", Map.of("state", "PROCESS_EXITED"));
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_stale_stop0001"))
        .thenReturn(Optional.of(iteration));

    boolean failed = mutationService.failStaleLaunchIfActive("bg_stale_stop0001");

    assertThat(failed).isFalse();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.QUEUED);
    assertThat(iteration.getRunSuccess()).isNull();
  }

  @Test
  void failStaleLaunchIfActive_ignoresTerminalRow() {
    PatchLoopIteration iteration = terminalIteration("bg_stale_terminal0001");
    iteration.setTaskMetadataJson(
        objectMapper.valueToTree(Map.of("target_preparation", Map.of("state", "PROCESS_EXITED"))));
    when(iterationRepository.findByIterationIdForUpdate("bg_stale_terminal0001"))
        .thenReturn(Optional.of(iteration));

    boolean failed = mutationService.failStaleLaunchIfActive("bg_stale_terminal0001");

    assertThat(failed).isFalse();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.FAILED);
  }

  // --- requestStop tests ---

  @Test
  void requestStop_persistsStopMetadataAndPreservesEntryId() {
    PatchLoopIteration iteration = newIteration("bg_stop0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "QUEUED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_stop0001"))
        .thenReturn(Optional.of(iteration));

    BuilderGoalRunStateMutationService.StopRequestResult result =
        mutationService.requestStop("bg_stop0001", "Operator clicked stop");

    assertThat(result.iterationId()).isEqualTo("bg_stop0001");
    assertThat(result.preservedEntryId()).isEqualTo(1001);
  }

  @Test
  void requestStop_rejectsAlreadyTerminalRun() {
    PatchLoopIteration iteration = terminalIteration("bg_stop_term0001");
    iteration.setTaskMetadataJson(objectMapper.valueToTree(new LinkedHashMap<>()));
    when(iterationRepository.findByIterationIdForUpdate("bg_stop_term0001"))
        .thenReturn(Optional.of(iteration));

    assertThatThrownBy(
            () -> mutationService.requestStop("bg_stop_term0001", "Operator clicked stop"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal");
  }

  // --- beginLateReconcile tests ---

  @Test
  void beginLateReconcile_returnsSnapshotForPublishFailedRun() {
    PatchLoopIteration iteration = newIteration("bg_pf0001");
    iteration.setStatus(PatchLoopStatus.FAILED);
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_pf0001"))
        .thenReturn(Optional.of(iteration));

    Optional<BuilderGoalRunStateMutationService.ReconcileSnapshot> snapshot =
        mutationService.beginLateReconcile("bg_pf0001");

    assertThat(snapshot).isPresent();
    assertThat(snapshot.get().entryId()).isEqualTo(1001);
    assertThat(snapshot.get().iterationId()).isEqualTo("bg_pf0001");
  }

  @Test
  void beginLateReconcile_rejectsNonFailedRuns() {
    PatchLoopIteration iteration = newIteration("bg_notfail0001");
    when(iterationRepository.findByIterationIdForUpdate("bg_notfail0001"))
        .thenReturn(Optional.of(iteration));

    Optional<BuilderGoalRunStateMutationService.ReconcileSnapshot> snapshot =
        mutationService.beginLateReconcile("bg_notfail0001");

    assertThat(snapshot).isEmpty();
  }

  @Test
  void beginLateReconcile_rejectsRunsWithNoEntryId() {
    PatchLoopIteration iteration = newIteration("bg_noentry0001");
    iteration.setStatus(PatchLoopStatus.FAILED);
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_noentry0001"))
        .thenReturn(Optional.of(iteration));

    Optional<BuilderGoalRunStateMutationService.ReconcileSnapshot> snapshot =
        mutationService.beginLateReconcile("bg_noentry0001");

    assertThat(snapshot).isEmpty();
  }

  @Test
  void beginLateReconcile_rejectsRunsWithStopRequest() {
    PatchLoopIteration iteration = newIteration("bg_stopreconcile0001");
    iteration.setStatus(PatchLoopStatus.FAILED);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("stop_requested_at", LocalDateTime.now().toString());
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_stopreconcile0001"))
        .thenReturn(Optional.of(iteration));

    Optional<BuilderGoalRunStateMutationService.ReconcileSnapshot> snapshot =
        mutationService.beginLateReconcile("bg_stopreconcile0001");

    assertThat(snapshot).isEmpty();
  }

  // --- applyLateReconcile tests ---

  @Test
  void applyLateReconcile_revivesQueuedRun() {
    PatchLoopIteration iteration = terminalIteration("bg_late0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "QUEUED",
            null,
            objectMapper.valueToTree(Map.of("status", "QUEUED")),
            "http://127.0.0.1:8098",
            1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late0001", 1001, "bg_late0001", "FISHING_TRAINING", executorResult);

    assertThat(result.revived()).isTrue();
    assertThat(result.reason()).isEqualTo("revived_queued");
  }

  @Test
  void applyLateReconcile_revivesRunningRun() {
    PatchLoopIteration iteration = terminalIteration("bg_late_running0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late_running0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "RUNNING",
            null,
            objectMapper.valueToTree(Map.of("status", "RUNNING", "task_started", true)),
            "http://127.0.0.1:8098",
            1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late_running0001", 1001, "bg_late_running0001", "FISHING_TRAINING", executorResult);

    assertThat(result.revived()).isTrue();
    assertThat(result.reason()).isEqualTo("revived_running");
  }

  @Test
  void applyLateReconcile_finalizesCompletedRun() {
    PatchLoopIteration iteration = terminalIteration("bg_late_complete0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late_complete0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "COMPLETED",
            null,
            objectMapper.valueToTree(Map.of("status", "COMPLETED")),
            "http://127.0.0.1:8098",
            1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late_complete0001",
            1001,
            "bg_late_complete0001",
            "FISHING_TRAINING",
            executorResult);

    assertThat(result.revived()).isTrue();
    assertThat(result.reason()).isEqualTo("completed");
  }

  @Test
  void applyLateReconcile_finalizesCancelledExecutorRun() {
    PatchLoopIteration iteration = terminalIteration("bg_late_cancelled0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late_cancelled0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "CANCELLED",
            null,
            objectMapper.valueToTree(Map.of("status", "CANCELLED")),
            "http://127.0.0.1:8098",
            1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late_cancelled0001",
            1001,
            "bg_late_cancelled0001",
            "FISHING_TRAINING",
            executorResult);

    assertThat(result.revived()).isTrue();
    assertThat(result.reason()).isEqualTo("cancelled");
  }

  @Test
  void applyLateReconcile_rejectsEntryIdMismatch() {
    PatchLoopIteration iteration = terminalIteration("bg_late_mismatch0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 999);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late_mismatch0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            true, "QUEUED", null, null, "http://127.0.0.1:8098", 1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late_mismatch0001",
            1001,
            "bg_late_mismatch0001",
            "FISHING_TRAINING",
            executorResult);

    assertThat(result.revived()).isFalse();
    assertThat(result.reason()).isEqualTo("entry_id_mismatch");
  }

  @Test
  void applyLateReconcile_rejectsNearMatchIterationIdentity() {
    PatchLoopIteration iteration = terminalIteration("bg_late_identity0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late_identity0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "RUNNING",
            null,
            objectMapper.valueToTree(
                Map.of("status", "RUNNING", "entry_id", 1001, "task_key", "FISHING_TRAINING")),
            "http://127.0.0.1:8098",
            1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late_identity0001",
            1001,
            "BG_LATE_IDENTITY0001",
            "FISHING_TRAINING",
            executorResult);

    assertThat(result.revived()).isFalse();
    assertThat(result.reason()).isEqualTo("iteration_id_mismatch");
  }

  @Test
  void applyLateReconcile_rejectsCancelledChromaScapeRun() {
    PatchLoopIteration iteration = cancelledIteration("bg_late_chroma_cancelled0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late_chroma_cancelled0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            true, "QUEUED", null, null, "http://127.0.0.1:8098", 1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late_chroma_cancelled0001",
            1001,
            "bg_late_chroma_cancelled0001",
            "FISHING_TRAINING",
            executorResult);

    assertThat(result.revived()).isFalse();
    assertThat(result.reason()).isEqualTo("already_cancelled");
  }

  @Test
  void applyLateReconcile_notFoundExecutorResultDoesNotRevive() {
    PatchLoopIteration iteration = terminalIteration("bg_late_notfound0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late_notfound0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            false, null, "not found", null, "http://127.0.0.1:8098", 1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late_notfound0001",
            1001,
            "bg_late_notfound0001",
            "FISHING_TRAINING",
            executorResult);

    assertThat(result.revived()).isFalse();
    assertThat(result.reason()).isEqualTo("executor_not_found");
  }

  @Test
  void applyLateReconcile_revivesAssignedRun() {
    PatchLoopIteration iteration = terminalIteration("bg_late_assigned0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "PUBLISH_FAILED");
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_late_assigned0001"))
        .thenReturn(Optional.of(iteration));

    ScriptExecutionService.Micro6RunStatusResult executorResult =
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "ASSIGNED",
            null,
            objectMapper.valueToTree(Map.of("status", "ASSIGNED")),
            "http://127.0.0.1:8098",
            1001);

    BuilderGoalRunStateMutationService.ReconcileApplyResult result =
        mutationService.applyLateReconcile(
            "bg_late_assigned0001",
            1001,
            "bg_late_assigned0001",
            "FISHING_TRAINING",
            executorResult);

    assertThat(result.revived()).isTrue();
    assertThat(result.reason()).isEqualTo("revived_queued");
  }

  // --- readMetadata tests ---

  @Test
  void readMetadata_returnsSnapshotWithoutLock() {
    PatchLoopIteration iteration = newIteration("bg_read0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 42);
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationId("bg_read0001")).thenReturn(Optional.of(iteration));

    BuilderGoalRunStateMutationService.MetadataSnapshot snapshot =
        mutationService.readMetadata("bg_read0001");

    assertThat(snapshot.iterationId()).isEqualTo("bg_read0001");
    assertThat(snapshot.status()).isEqualTo(PatchLoopStatus.QUEUED);
    assertThat(snapshot.taskMetadata()).isNotNull();
    assertThat(snapshot.publishMetadata()).containsEntry("entry_id", 42);
  }
}
