package com.chromascape.web.debug.buildergoal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.patchloop.entity.PatchLoopIteration;
import com.chromascape.web.debug.patchloop.entity.PatchLoopIterationRepository;
import com.chromascape.web.debug.patchloop.entity.PatchLoopStatus;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BuilderGoalRepairIncidentServiceTest {

  @Mock private PatchLoopIterationRepository iterationRepository;
  @Mock private BuilderGoalRunStateMutationService mutationService;

  private final AtomicLong clock = new AtomicLong(1_000_000L);
  private BuilderGoalRepairIncidentService service;
  private PatchLoopIteration iteration;

  @BeforeEach
  void setUp() {
    service =
        new BuilderGoalRepairIncidentService(
            iterationRepository, mutationService, 30L, 60L, clock::get);
    iteration = new PatchLoopIteration();
    iteration.setIterationId("iteration-1");
    iteration.setScriptName("FISHING_TRAINING");
    iteration.setSourceType("builder_goal");
    iteration.setStatus(PatchLoopStatus.RUNNING_SCRIPT);
    iteration.setRunSessionId("session-1");
    when(iterationRepository.findFirstBySourceTypeAndRunSessionIdOrderByCreatedAtDesc(
            "builder_goal", "session-1"))
        .thenReturn(Optional.of(iteration));
  }

  @Test
  void errorLogCreatesReviewOnlyIncident() {
    TickBatchMessage batch = batch();
    TickBatchMessage.TickLogEntry error =
        new TickBatchMessage.TickLogEntry(
            "TASK", "ERROR", "EXCEPTION", "Task failed at step 42", Map.of(), null);
    batch.setLogs(List.of(error));

    service.observe(batch);

    ArgumentCaptor<Map<String, Object>> incident = ArgumentCaptor.forClass(Map.class);
    verify(mutationService).recordRepairIncident(eq("iteration-1"), incident.capture());
    assertThat(incident.getValue())
        .containsEntry("type", "EXCEPTION")
        .containsEntry("state", "OPEN")
        .containsEntry("session_id", "session-1");
  }

  @Test
  void unchangedFingerprintCreatesStallAfterThreshold() {
    TickBatchMessage batch = batch();
    service.observe(batch);
    clock.addAndGet(31_000L);

    service.observe(batch);

    ArgumentCaptor<Map<String, Object>> incident = ArgumentCaptor.forClass(Map.class);
    verify(mutationService).recordRepairIncident(eq("iteration-1"), incident.capture());
    assertThat(incident.getValue())
        .containsEntry("type", "STALL")
        .containsEntry("stagnant_seconds", 31L);
  }

  @Test
  void intentionalWaitResetsStallClock() {
    TickBatchMessage batch = batch();
    service.observe(batch);
    clock.addAndGet(31_000L);
    batch.setMicrobotStatus("Waiting for login");

    service.observe(batch);

    verify(mutationService, never())
        .recordRepairIncident(eq("iteration-1"), org.mockito.ArgumentMatchers.any());
  }

  private TickBatchMessage batch() {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setSessionId("session-1");
    batch.setTick(500L);
    batch.setActiveTaskKey("FISHING_TRAINING");
    batch.setActivePluginClasses(List.of("example.AccountBuilderPlugin"));
    batch.setMicrobotStatus("Fishing");
    Map<String, Object> player = new HashMap<>();
    player.put("worldX", 3200);
    player.put("worldY", 3200);
    player.put("animation", 622);
    batch.setPlayerState(player);
    batch.setInventory(List.of(Map.of("id", 317, "quantity", 1)));
    batch.setLogs(List.of());
    return batch;
  }
}
