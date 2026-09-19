package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.entity.PatchLoopEventType;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Verifies that the locked run-state mutations emit the SC-260 unified run timeline events
 * (complete/fail, pause/resume, stop) with the durable failure-reason contract.
 */
@ExtendWith(MockitoExtension.class)
class BuilderGoalRunStateMutationServiceEventTest {

  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private PatchLoopPersistenceService persistenceService;
  @Mock private BuilderGoalRunEventService runEventService;

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private BuilderGoalRunStateMutationService mutationService;

  @BeforeEach
  void setUp() {
    mutationService =
        new BuilderGoalRunStateMutationService(
            iterationRepository,
            persistenceService,
            objectMapper,
            runEventService,
            300_000L,
            3,
            15_000L);
  }

  private PatchLoopIteration publishedIteration(String iterationId, String publishStatus) {
    PatchLoopIteration iteration = new PatchLoopIteration();
    iteration.setIterationId(iterationId);
    iteration.setScriptName("FISHING_TRAINING");
    iteration.setStatus(PatchLoopStatus.QUEUED);
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
      String status, String errorMessage) {
    return new ScriptExecutionService.Micro6RunStatusResult(
        true,
        status,
        errorMessage,
        objectMapper.valueToTree(new LinkedHashMap<String, Object>()),
        "http://127.0.0.1:8098",
        1001);
  }

  @Test
  @DisplayName("first active dispatch state emits SCRIPT_STARTED")
  void runningAfterQueued_emitsScriptStarted() {
    PatchLoopIteration iteration = publishedIteration("bg_ev0001", "RECEIVED");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0001"))
        .thenReturn(Optional.of(iteration));

    mutationService.applyExecutorStatus(
        "bg_ev0001", 1001, executorStatus("RUNNING", null), LocalDateTime.now());

    verify(runEventService)
        .recordRunEvent(
            eq("bg_ev0001"),
            eq(PatchLoopEventType.SCRIPT_STARTED),
            anyString(),
            anyString(),
            any());
  }

  @Test
  @DisplayName("transition to PAUSED emits RUN_PAUSED and resume emits RUN_RESUMED")
  void pauseAndResumeTransitions_emitTimelineEvents() {
    PatchLoopIteration paused = publishedIteration("bg_ev0002", "RUNNING");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0002"))
        .thenReturn(Optional.of(paused));
    mutationService.applyExecutorStatus(
        "bg_ev0002",
        1001,
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "PAUSED",
            null,
            objectMapper.valueToTree(Map.of("status", "PAUSED")),
            "http://127.0.0.1:8098",
            1001),
        LocalDateTime.now());
    verify(runEventService)
        .recordRunEvent(
            eq("bg_ev0002"), eq(PatchLoopEventType.RUN_PAUSED), anyString(), anyString(), any());

    PatchLoopIteration resumed = publishedIteration("bg_ev0003", "PAUSED");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0003"))
        .thenReturn(Optional.of(resumed));
    mutationService.applyExecutorStatus(
        "bg_ev0003", 1001, executorStatus("RUNNING", null), LocalDateTime.now());
    verify(runEventService)
        .recordRunEvent(
            eq("bg_ev0003"), eq(PatchLoopEventType.RUN_RESUMED), anyString(), anyString(), any());
  }

  @Test
  @DisplayName("completed runs emit SCRIPT_COMPLETED with run success")
  void completionEmitsCompletedEvent() {
    PatchLoopIteration iteration = publishedIteration("bg_ev0004", "RUNNING");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0004"))
        .thenReturn(Optional.of(iteration));

    mutationService.applyExecutorStatus(
        "bg_ev0004", 1001, executorStatus("COMPLETED", null), LocalDateTime.now());

    verify(runEventService)
        .recordFailureEvent(eq("bg_ev0004"), eq(1001), eq(true), isNull(), isNull());
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName("timeline events are deferred until the enclosing state transaction commits")
  void completionDefersEventUntilAfterCommit() {
    PatchLoopIteration iteration = publishedIteration("bg_ev_after_commit", "RUNNING");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev_after_commit"))
        .thenReturn(Optional.of(iteration));
    TransactionSynchronizationManager.initSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    try {
      mutationService.applyExecutorStatus(
          "bg_ev_after_commit", 1001, executorStatus("COMPLETED", null), LocalDateTime.now());

      verify(runEventService, never())
          .recordFailureEvent(anyString(), any(), eq(true), isNull(), isNull());
      for (TransactionSynchronization synchronization :
          TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCommit();
      }
      verify(runEventService)
          .recordFailureEvent(eq("bg_ev_after_commit"), eq(1001), eq(true), isNull(), isNull());
    } finally {
      TransactionSynchronizationManager.setActualTransactionActive(false);
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("failed runs emit SCRIPT_FAILED carrying the durable failure contract")
  void failureEmitsFailedEventWithContract() {
    PatchLoopIteration iteration = publishedIteration("bg_ev0005", "RUNNING");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0005"))
        .thenReturn(Optional.of(iteration));

    mutationService.applyExecutorStatus(
        "bg_ev0005", 1001, executorStatus("FAILED", "Script crashed"), LocalDateTime.now());

    verify(runEventService)
        .recordFailureEvent(
            eq("bg_ev0005"),
            eq(1001),
            eq(false),
            eq(PatchLoopFailureCode.SCRIPT_EXCEPTION),
            contains("Script crashed"));
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.FAILED);
  }

  @Test
  @DisplayName("confirmed cancellation emits RUN_STOPPED with the stop reason")
  void cancellationEmitsStoppedEvent() {
    PatchLoopIteration iteration = publishedIteration("bg_ev0006", "RUNNING");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0006"))
        .thenReturn(Optional.of(iteration));

    mutationService.applyExecutorStatus(
        "bg_ev0006",
        1001,
        executorStatus("CANCELLED", "Stopped from debugger"),
        LocalDateTime.now());

    verify(runEventService)
        .recordRunEvent(
            eq("bg_ev0006"),
            eq(PatchLoopEventType.RUN_STOPPED),
            contains("Stopped from debugger"),
            anyString(),
            any());
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.CANCELLED);
  }

  @Test
  @DisplayName("executor risk payloads emit RUN_RISK_TRIGGERED")
  void riskPayloadEmitsRiskEvent() {
    PatchLoopIteration iteration = publishedIteration("bg_ev0007", "RUNNING");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0007"))
        .thenReturn(Optional.of(iteration));
    Map<String, Object> raw = new LinkedHashMap<>();
    Map<String, Object> risk = new LinkedHashMap<>();
    risk.put("level", "HIGH");
    raw.put("risk", risk);

    mutationService.applyExecutorStatus(
        "bg_ev0007",
        1001,
        new ScriptExecutionService.Micro6RunStatusResult(
            true, "RUNNING", null, objectMapper.valueToTree(raw), "http://127.0.0.1:8098", 1001),
        LocalDateTime.now());

    verify(runEventService).recordRiskEvent(eq("bg_ev0007"), eq(1001), any());
  }

  @Test
  @DisplayName("no recorder wired: mutations succeed without timeline events")
  void missingRecorder_keepsMutationsWorking() {
    BuilderGoalRunStateMutationService bare =
        new BuilderGoalRunStateMutationService(
            iterationRepository, persistenceService, objectMapper);
    PatchLoopIteration iteration = publishedIteration("bg_ev0008", "RUNNING");
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0008"))
        .thenReturn(Optional.of(iteration));

    BuilderGoalRunStateMutationService.StatusApplyResult result =
        bare.applyExecutorStatus(
            "bg_ev0008", 1001, executorStatus("COMPLETED", null), LocalDateTime.now());

    assertThat(result.terminal()).isTrue();
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.COMPLETED);
  }

  @Test
  @DisplayName(
      "task start confirmation flip under an unchanged RUNNING status emits SCRIPT_STARTED")
  void taskStartConfirmationFlip_emitsScriptStarted() {
    PatchLoopIteration iteration = publishedIteration("bg_ev0009", "RUNNING");
    // The previous poll saw RUNNING with task_started=false, so the persisted confirmation
    // flag is false. This poll reports task_started=true with a debug session id.
    java.util.Map<String, Object> metadata =
        objectMapper.convertValue(iteration.getTaskMetadataJson(), java.util.Map.class);
    metadata.put("task_start_confirmed", false);
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0009"))
        .thenReturn(Optional.of(iteration));

    mutationService.applyExecutorStatus(
        "bg_ev0009",
        1001,
        new ScriptExecutionService.Micro6RunStatusResult(
            true,
            "RUNNING",
            null,
            objectMapper.valueToTree(
                Map.of(
                    "status", "RUNNING",
                    "task_started", true,
                    "debug_session_id", "session-9")),
            "http://127.0.0.1:8098",
            1001),
        LocalDateTime.now());

    verify(runEventService)
        .recordRunEvent(
            eq("bg_ev0009"),
            eq(PatchLoopEventType.SCRIPT_STARTED),
            anyString(),
            anyString(),
            any());
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.RUNNING_SCRIPT);
  }

  @Test
  @DisplayName("task-start timeout records SCRIPT_FAILED with the timeout reason")
  void taskStartTimeout_recordsFailedEvent() {
    PatchLoopIteration iteration = publishedIteration("bg_ev0010", "RECEIVED");
    java.util.Map<String, Object> raw =
        new java.util.LinkedHashMap<>(java.util.Map.of("status", "RECEIVED"));
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0010"))
        .thenReturn(Optional.of(iteration));
    // published_at was set far enough in the past to exceed the run timeout (300s configured).
    java.util.Map<String, Object> metadata =
        objectMapper.convertValue(iteration.getTaskMetadataJson(), java.util.Map.class);
    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> publish =
        (java.util.Map<String, Object>) metadata.get("micro6_publish");
    publish.put("published_at", LocalDateTime.now().minusMinutes(10).toString());
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));

    mutationService.applyExecutorStatus(
        "bg_ev0010", 1001, executorStatus("RECEIVED", null), LocalDateTime.now());

    verify(runEventService)
        .recordFailureEvent(
            eq("bg_ev0010"),
            eq(1001),
            eq(false),
            eq(PatchLoopFailureCode.SCRIPT_START_FAILED),
            contains("Task start timed out"));
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.FAILED);
  }

  @Test
  @DisplayName("pre-publish stop leaves timeline ordering to the runner after state commit")
  void prePublishStop_doesNotRecordStoppedBeforeRequested() {
    PatchLoopIteration iteration = publishedIteration("bg_ev0011", "PUBLISHING");
    // Pre-publish: no entry id yet.
    java.util.Map<String, Object> metadata =
        objectMapper.convertValue(iteration.getTaskMetadataJson(), java.util.Map.class);
    metadata.remove("micro6_publish");
    iteration.setTaskMetadataJson(objectMapper.valueToTree(metadata));
    when(iterationRepository.findByIterationIdForUpdate("bg_ev0011"))
        .thenReturn(Optional.of(iteration));

    mutationService.requestStop("bg_ev0011", "Operator removed the task");

    verify(runEventService, never())
        .recordRunEvent(
            eq("bg_ev0011"), eq(PatchLoopEventType.RUN_STOPPED), anyString(), anyString(), any());
    assertThat(iteration.getStatus()).isEqualTo(PatchLoopStatus.CANCELLED);
  }
}
