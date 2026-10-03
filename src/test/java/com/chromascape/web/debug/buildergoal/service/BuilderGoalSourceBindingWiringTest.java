package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * SC-810 regression cover for the source-binding gap.
 *
 * <p>Builder-goal dispatch persisted no run-to-snapshot binding at all: a search of every text and
 * JSON column in the schema found no snapshot id, artifact digest or build id for an accepted run,
 * so once the executor stopped, nothing recorded which source that run had executed. These tests
 * pin the fix and, equally importantly, pin the states that must never silently look like success.
 */
@ExtendWith(MockitoExtension.class)
class BuilderGoalSourceBindingWiringTest {

  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private PatchLoopPersistenceService persistenceService;
  @Mock private BuilderGoalSourceBindingService bindingService;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  private BuilderGoalRunStateMutationService serviceWithBinder() {
    return new BuilderGoalRunStateMutationService(
        iterationRepository,
        persistenceService,
        objectMapper,
        300_000L,
        3,
        15_000L,
        bindingService);
  }

  private BuilderGoalRunStateMutationService serviceWithoutBinder() {
    return new BuilderGoalRunStateMutationService(
        iterationRepository, persistenceService, objectMapper);
  }

  private PatchLoopIteration runningIteration(String iterationId, String taskKey) {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId(iterationId);
    iteration.setScriptName("FISHING_TRAINING");
    iteration.setStatus(PatchLoopStatus.QUEUED);
    iteration.setRunRecordingId("recording_test0001");
    Map<String, Object> metadata = new LinkedHashMap<>();
    Map<String, Object> publish = new LinkedHashMap<>();
    publish.put("entry_id", 1001);
    publish.put("status", "RUNNING");
    publish.put("published_at", LocalDateTime.now().minusSeconds(1).toString());
    if (taskKey != null) {
      publish.put("task_key", taskKey);
    }
    metadata.put("micro6_publish", publish);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    return iteration;
  }

  private ScriptExecutionService.Micro6RunStatusResult confirmedRunning() {
    return new ScriptExecutionService.Micro6RunStatusResult(
        true,
        "RUNNING",
        null,
        objectMapper.valueToTree(
            Map.of("status", "RUNNING", "task_started", true, "debug_session_id", "debug-1")),
        "http://127.0.0.1:8098",
        1001);
  }

  @Test
  void bindsSourceOnceTaskStartIsConfirmed() {
    PatchLoopIteration iteration = runningIteration("bg_bind_0001", "FISHING_TRAINING");
    when(iterationRepository.findByIterationIdForUpdate("bg_bind_0001"))
        .thenReturn(Optional.of(iteration));
    when(bindingService.bindRun(anyString(), any(), anyString(), any(), any()))
        .thenReturn(
            new BuilderGoalSourceBindingService.BindingOutcome(
                BuilderGoalSourceBindingService.STATE_BOUND, null, null));

    serviceWithBinder()
        .applyExecutorStatus("bg_bind_0001", 1001, confirmedRunning(), LocalDateTime.now());

    verify(bindingService)
        .bindRun(
            eq("bg_bind_0001"), eq("recording_test0001"), eq("FISHING_TRAINING"), any(), any());
    assertThat(iteration.getSourceBindingState())
        .isEqualTo(BuilderGoalSourceBindingService.STATE_BOUND);
  }

  @Test
  void doesNotBindBeforeTaskStartIsConfirmed() {
    PatchLoopIteration iteration = runningIteration("bg_bind_0002", "FISHING_TRAINING");
    when(iterationRepository.findByIterationIdForUpdate("bg_bind_0002"))
        .thenReturn(Optional.of(iteration));

    // The executor has not started the task; recorder availability is independent.
    ScriptExecutionService.Micro6RunStatusResult unconfirmed =
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "RUNNING",
            null,
            objectMapper.valueToTree(Map.of("status", "RUNNING", "task_started", false)),
            "http://127.0.0.1:8098",
            1001);

    serviceWithBinder().applyExecutorStatus("bg_bind_0002", 1001, unconfirmed, LocalDateTime.now());

    verify(bindingService, never()).bindRun(anyString(), any(), anyString(), any(), any());
    assertThat(iteration.getSourceBindingState()).isNull();
  }

  @Test
  void bindsSourceWithoutWaitingForRecorderSession() {
    PatchLoopIteration iteration = runningIteration("bg_bind_no_recorder", "FISHING_TRAINING");
    when(iterationRepository.findByIterationIdForUpdate("bg_bind_no_recorder"))
        .thenReturn(Optional.of(iteration));
    when(bindingService.bindRun(anyString(), any(), anyString(), any(), any()))
        .thenReturn(
            new BuilderGoalSourceBindingService.BindingOutcome(
                BuilderGoalSourceBindingService.STATE_BOUND, null, null));
    serviceWithBinder()
        .applyExecutorStatus(
            "bg_bind_no_recorder",
            1001,
            new ScriptExecutionService.Micro6RunStatusResult(
                true,
                "RUNNING",
                null,
                objectMapper.valueToTree(Map.of("task_started", true)),
                "http://127.0.0.1:8098",
                1001),
            LocalDateTime.now());
    verify(bindingService)
        .bindRun(
            eq("bg_bind_no_recorder"),
            eq("recording_test0001"),
            eq("FISHING_TRAINING"),
            any(),
            any());
    assertThat(iteration.getSourceBindingState())
        .isEqualTo(BuilderGoalSourceBindingService.STATE_BOUND);
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.RUNNING_SCRIPT);
  }

  @Test
  void bindsExactlyOnceAcrossRepeatedStatusPolls() {
    PatchLoopIteration iteration = runningIteration("bg_bind_0003", "FISHING_TRAINING");
    when(iterationRepository.findByIterationIdForUpdate("bg_bind_0003"))
        .thenReturn(Optional.of(iteration));
    when(bindingService.bindRun(anyString(), any(), anyString(), any(), any()))
        .thenReturn(
            new BuilderGoalSourceBindingService.BindingOutcome(
                BuilderGoalSourceBindingService.STATE_BOUND, null, null));

    BuilderGoalRunStateMutationService service = serviceWithBinder();
    service.applyExecutorStatus("bg_bind_0003", 1001, confirmedRunning(), LocalDateTime.now());
    service.applyExecutorStatus("bg_bind_0003", 1001, confirmedRunning(), LocalDateTime.now());
    service.applyExecutorStatus("bg_bind_0003", 1001, confirmedRunning(), LocalDateTime.now());

    verify(bindingService, times(1)).bindRun(anyString(), any(), anyString(), any(), any());
    assertThat(iteration.getSourceBindingState())
        .isEqualTo(BuilderGoalSourceBindingService.STATE_BOUND);
  }

  @Test
  void recordsNotConfiguredRatherThanLeavingStateNullWhenBinderIsAbsent() {
    PatchLoopIteration iteration = runningIteration("bg_bind_0004", "FISHING_TRAINING");
    when(iterationRepository.findByIterationIdForUpdate("bg_bind_0004"))
        .thenReturn(Optional.of(iteration));

    serviceWithoutBinder()
        .applyExecutorStatus("bg_bind_0004", 1001, confirmedRunning(), LocalDateTime.now());

    // The whole point of the column: "not bound" must be explicit, never indistinguishable from
    // a run that was simply never examined.
    assertThat(iteration.getSourceBindingState())
        .isEqualTo(BuilderGoalSourceBindingService.STATE_NOT_CONFIGURED);
  }

  @Test
  void recordsFailedStateButDoesNotFailTheRunningRun() {
    PatchLoopIteration iteration = runningIteration("bg_bind_0005", "FISHING_TRAINING");
    when(iterationRepository.findByIterationIdForUpdate("bg_bind_0005"))
        .thenReturn(Optional.of(iteration));
    when(bindingService.bindRun(anyString(), any(), anyString(), any(), any()))
        .thenReturn(
            new BuilderGoalSourceBindingService.BindingOutcome(
                BuilderGoalSourceBindingService.STATE_FAILED, null, "manifest unavailable"));

    serviceWithBinder()
        .applyExecutorStatus("bg_bind_0005", 1001, confirmedRunning(), LocalDateTime.now());

    assertThat(iteration.getSourceBindingState())
        .isEqualTo(BuilderGoalSourceBindingService.STATE_FAILED);
    // The client is already executing; a provenance failure must not destroy live work.
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.RUNNING_SCRIPT);
    assertThat(iteration.getRunSuccess()).isNull();
  }

  @Test
  void recordsFailedWhenNoTaskKeyIsAvailableToBind() {
    PatchLoopIteration iteration = runningIteration("bg_bind_0006", null);
    when(iterationRepository.findByIterationIdForUpdate("bg_bind_0006"))
        .thenReturn(Optional.of(iteration));

    serviceWithBinder()
        .applyExecutorStatus("bg_bind_0006", 1001, confirmedRunning(), LocalDateTime.now());

    verify(bindingService, never()).bindRun(anyString(), any(), anyString(), any(), any());
    assertThat(iteration.getSourceBindingState())
        .isEqualTo(BuilderGoalSourceBindingService.STATE_FAILED);
  }

  @Test
  void passesNullAttestationWhenHeartbeatCarriesNoArtifactIdentity() {
    PatchLoopIteration iteration = runningIteration("bg_bind_0007", "FISHING_TRAINING");
    when(iterationRepository.findByIterationIdForUpdate("bg_bind_0007"))
        .thenReturn(Optional.of(iteration));
    when(bindingService.bindRun(anyString(), any(), anyString(), any(), any()))
        .thenReturn(
            new BuilderGoalSourceBindingService.BindingOutcome(
                BuilderGoalSourceBindingService.STATE_BOUND, null, null));

    serviceWithBinder()
        .applyExecutorStatus("bg_bind_0007", 1001, confirmedRunning(), LocalDateTime.now());

    // Today's heartbeat has no artifact identity. The call site must pass null rather than
    // substitute the manifest's own values, which would be a self-referential "verification".
    verify(bindingService)
        .bindRun(eq("bg_bind_0007"), any(), eq("FISHING_TRAINING"), isNull(), isNull());
  }
}
