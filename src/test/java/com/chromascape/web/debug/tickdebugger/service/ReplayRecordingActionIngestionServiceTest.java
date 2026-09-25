package com.chromascape.web.debug.tickdebugger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chromascape.web.debug.tickdebugger.dto.RecordingAction;
import com.chromascape.web.debug.tickdebugger.dto.TickBatchMessage;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingAction;
import com.chromascape.web.debug.tickdebugger.entity.ReplayRecordingActionRepository;
import com.chromascape.web.replay.projection.CanonicalProjectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReplayRecordingActionIngestionServiceTest {

  @Mock private ReplayRecordingActionRepository repository;

  private ReplayRecordingActionIngestionService service;

  @BeforeEach
  void setUp() {
    service =
        new ReplayRecordingActionIngestionService(
            repository, new ObjectMapper(), new CanonicalProjectionService());
  }

  @Test
  void acceptsTheMicro6ChecksumAndRetainsTheProducerIdentity() {
    RecordingAction action = action();
    assertThat(action.getContentChecksum())
        .isEqualTo("sha256:4ef4c963fe183e1c91ac29e8d612a3e61e4a2f6dd3033fe109e0a9afc97e3547");
    TickBatchMessage batch = batch(action);
    ReplayRecordingAction stored = new ReplayRecordingAction();
    stored.setRecordingId("recording-1");
    stored.setProvenance(RecordingAction.PROVENANCE_CAPTURED);
    stored.setContentChecksum(action.getContentChecksum());
    when(repository.findBySessionIdAndTickAndSequence("session-1", 12L, 3))
        .thenReturn(Optional.empty(), Optional.of(stored));
    when(repository.insertIfAbsent(
            any(), any(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any()))
        .thenReturn(1);

    List<ReplayRecordingAction> persisted = service.ingest(batch, "recording-1");

    assertThat(persisted).hasSize(1);
    assertThat(persisted.get(0).getRecordingId()).isEqualTo("recording-1");
    assertThat(persisted.get(0).getProvenance()).isEqualTo(RecordingAction.PROVENANCE_CAPTURED);
    assertThat(persisted.get(0).getContentChecksum()).isEqualTo(action.getContentChecksum());
  }

  @Test
  void acceptsLexicographicOrderingWhenSequenceRestartsOnANewTick() {
    RecordingAction first = actionAt(10, 2);
    RecordingAction second = actionAt(11, 0);
    TickBatchMessage batch = batchWithActions(first, second, 11);

    ReplayRecordingAction firstStored = stored(first);
    ReplayRecordingAction secondStored = stored(second);
    when(repository.findBySessionIdAndTickAndSequence(any(), anyLong(), anyInt()))
        .thenReturn(
            Optional.empty(),
            Optional.of(firstStored),
            Optional.empty(),
            Optional.of(secondStored));
    when(repository.insertIfAbsent(
            any(), any(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any()))
        .thenReturn(1, 1);

    assertThat(service.ingest(batch, "recording-1")).containsExactly(firstStored, secondStored);
  }

  @Test
  void comparesTheWinnerAfterNativeConflictWithoutSaveAndFlush() {
    RecordingAction action = action();
    ReplayRecordingAction winner = stored(action);
    when(repository.findBySessionIdAndTickAndSequence("session-1", 12L, 3))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(repository.insertIfAbsent(
            any(), any(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any()))
        .thenReturn(0);

    assertThat(service.ingest(batch(action), "recording-1")).containsExactly(winner);
    verify(repository, never()).saveAndFlush(any(ReplayRecordingAction.class));
  }

  @Test
  void rejectsAConflictingNativeWinnerByProducerChecksum() {
    RecordingAction action = action();
    ReplayRecordingAction winner = stored(action);
    winner.setContentChecksum(
        "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
    when(repository.findBySessionIdAndTickAndSequence("session-1", 12L, 3))
        .thenReturn(Optional.of(winner));

    assertThatThrownBy(() -> service.ingest(batch(action), "recording-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ACTION_CHECKSUM_CONFLICT");
    verify(repository, never())
        .insertIfAbsent(
            any(), any(), anyLong(), anyInt(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any());
  }

  @Test
  void rejectsTamperedArgumentsByContentChecksum() {
    RecordingAction action = action();
    action.getArguments().put("option", "talk-to");

    assertThatThrownBy(() -> service.ingest(batch(action), "recording-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contentChecksum");
  }

  @Test
  void rejectsAnActionBoundToAnotherRecording() {
    RecordingAction action = action();

    assertThatThrownBy(() -> service.ingest(batch(action), "recording-2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("recordingId");
  }

  private TickBatchMessage batchWithActions(
      RecordingAction first, RecordingAction second, long tick) {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setSessionId("session-1");
    batch.setTick(tick);
    batch.setActions(List.of(first, second));
    return batch;
  }

  private ReplayRecordingAction stored(RecordingAction action) {
    ReplayRecordingAction stored = new ReplayRecordingAction();
    stored.setSessionId(action.getSessionId());
    stored.setRecordingId("recording-1");
    stored.setTick(action.getTick());
    stored.setSequence(action.getSequence());
    stored.setContentChecksum(action.getContentChecksum());
    stored.setProvenance(RecordingAction.PROVENANCE_CAPTURED);
    return stored;
  }

  private RecordingAction actionAt(long tick, int sequence) {
    RecordingAction action = action();
    action.setTick(tick);
    action.setSequence(sequence);
    action.setContentChecksum(service.computeContentChecksum(action));
    return action;
  }

  private TickBatchMessage batch(RecordingAction action) {
    TickBatchMessage batch = new TickBatchMessage();
    batch.setSessionId("session-1");
    batch.setTick(12);
    batch.setActions(List.of(action));
    return batch;
  }

  private RecordingAction action() {
    RecordingAction action = new RecordingAction();
    action.setRecordingId("recording-1");
    action.setSessionId("session-1");
    action.setTick(12);
    action.setSequence(3);
    action.setActionType("INTERACT_NPC");
    action.setTarget(Map.of("kind", "NPC", "stableId", "npc:1518:3200:3200:0"));
    action.setArguments(new java.util.HashMap<>(Map.of("option", "net")));
    action.setSource(RecordingAction.SOURCE_MICRO6_SEMANTIC_DISPATCH);
    action.setProvenance(RecordingAction.PROVENANCE_CAPTURED);
    action.setConfidence(BigDecimal.ONE);
    action.setCapturedAt("2026-08-31T22:00:00Z");
    action.setContentChecksum(service.computeContentChecksum(action));
    return action;
  }
}
