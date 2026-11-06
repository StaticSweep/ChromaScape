package com.chromascape.web.aiplayer.contract;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.chromascape.web.aiplayer.routine.entity.RoutineCaptureRecord;
import com.chromascape.web.aiplayer.routine.repository.*;
import com.chromascape.web.aiplayer.routine.service.*;
import com.chromascape.web.debug.tickdebugger.entity.TickSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class CurrentRecordingBindingTest {
  @Test
  void finalizedProvenanceMustMatchRecordingHashAndActualDebugSession() throws Exception {
    var mapper = new ObjectMapper();
    CurrentSliceIdentity before;
    try (var input = getClass().getResourceAsStream("/sc663/runelite-identity.json")) {
      before = mapper.readValue(input, CurrentSliceIdentity.class);
    }
    var identity =
        new CurrentSliceIdentity(
            before.schema(),
            before.task(),
            null,
            before.client(),
            new CurrentSliceIdentity.Capture("capture", "recording", "sha256:" + "a".repeat(64)),
            null,
            null,
            null,
            null,
            false);
    var row = new RoutineCaptureRecord();
    row.setCompleted(true);
    row.setDebugSessionId("debug");
    row.setRecordingHash(identity.capture().recordingHash());
    row.setIdentityJson(mapper.writeValueAsString(identity));
    var rows = mock(RoutineCaptureRecordRepository.class);
    when(rows.findByRecordingId("recording")).thenReturn(Optional.of(row));
    var service =
        new RoutineCaptureIngestionService(
            mapper,
            new CurrentSliceIdentityValidator(),
            rows,
            mock(RoutineCaptureOperationRepository.class),
            mock(RoutineCaptureBatchRepository.class),
            mock(CurrentSliceRunRepository.class),
            mock(CaptureOperationDispatcher.class),
            mock(TickSnapshotRepository.class),
            mock(ApplicationEventPublisher.class));
    assertThat(service.recordingIdentity("recording", "model", "debug")).isEqualTo(identity);
    assertThatThrownBy(() -> service.recordingIdentity("recording", "model", "other-debug"))
        .isInstanceOf(CurrentSliceContractException.class);
    row.setRecordingHash("sha256:" + "b".repeat(64));
    assertThatThrownBy(() -> service.recordingIdentity("recording", "model", "debug"))
        .isInstanceOf(CurrentSliceContractException.class);
    row.setRecordingHash(identity.capture().recordingHash());
    row.setCompleted(false);
    assertThatThrownBy(() -> service.recordingIdentity("recording", "model", "debug"))
        .isInstanceOf(CurrentSliceContractException.class);
    verify(rows, never()).save(any());
  }
}
